package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore.AcquireResult;
import it.unimib.datai.nanofaas.controlplane.execution.Outcome;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

@Service
public final class InvocationExecutionFactory {
    private final ExecutionStore executionStore;
    private final IdempotencyStore idempotencyStore;

    private final Metrics metrics;

    public InvocationExecutionFactory(ExecutionStore executionStore, IdempotencyStore idempotencyStore,
                                      Metrics metrics) {
        this.metrics = metrics;
        this.executionStore = executionStore;
        this.idempotencyStore = idempotencyStore;
    }

    public ExecutionLookup createOrReuseExecution(String functionName,
                                                  FunctionSpec spec,
                                                  InvocationRequest request,
                                                  String idempotencyKey,
                                                  String traceId,
                                                  InvocationKind kind) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            ExecutionRecord executionRecord = newExecutionRecord(functionName, spec, request, null, traceId, kind);
            executionStore.put(executionRecord);
            return ExecutionLookup.newUnclaimed(executionRecord, executionStore);
        }

        while (true) {
            AcquireResult acquire = idempotencyStore.acquireOrGet(functionName, idempotencyKey);
            if (acquire.state() == AcquireResult.State.CLAIMED) {
                return createClaimedRecord(
                        functionName,
                        spec,
                        request,
                        idempotencyKey,
                        traceId,
                        kind,
                        acquire.executionIdOrToken()
                );
            }
            if (acquire.state() == AcquireResult.State.PENDING) {
                parkPendingClaim();
                continue;
            }

            String existingExecutionId = acquire.executionIdOrToken();
            ExecutionRecord existing = executionStore.getOrNull(existingExecutionId);
            if (existing != null) {
                // The key did its job: a second arrival found the first execution and will
                // wait on its result instead of running the function again.
                metrics.replayed(functionName, kind);
                return ExecutionLookup.existing(existing);
            }

            // Finita e archiviata. Guardare solo fra i vivi la farebbe passare per una
            // rivendicazione stantia, e la funzione girerebbe una seconda volta in
            // silenzio: esattamente il fallimento che la chiave esiste per impedire.
            Outcome settledOutcome = executionStore.outcomeOf(existingExecutionId);
            if (settledOutcome != null) {
                metrics.replayed(functionName, kind);
                return ExecutionLookup.settled(existingExecutionId, settledOutcome);
            }

            AcquireResult staleClaim = idempotencyStore.claimIfMatches(functionName, idempotencyKey, existingExecutionId);
            if (staleClaim.state() == AcquireResult.State.CLAIMED) {
                return createClaimedRecord(
                        functionName,
                        spec,
                        request,
                        idempotencyKey,
                        traceId,
                        kind,
                        staleClaim.executionIdOrToken()
                );
            }
            if (staleClaim.state() == AcquireResult.State.PENDING) {
                parkPendingClaim();
            }
        }
    }

    private ExecutionLookup createClaimedRecord(String functionName,
                                                FunctionSpec spec,
                                                InvocationRequest request,
                                                String idempotencyKey,
                                                String traceId,
                                                InvocationKind kind,
                                                String claimToken) {
        ExecutionRecord executionRecord = newExecutionRecord(functionName, spec, request, idempotencyKey, traceId, kind);
        try {
            executionStore.put(executionRecord);
            return ExecutionLookup.newClaimed(
                    executionRecord,
                    executionStore,
                    idempotencyStore,
                    functionName,
                    idempotencyKey,
                    claimToken
            );
        } catch (RuntimeException ex) {
            executionStore.remove(executionRecord.executionId());
            idempotencyStore.abandonClaim(functionName, idempotencyKey, claimToken);
            throw ex;
        }
    }

    private static ExecutionRecord newExecutionRecord(String functionName,
                                                      FunctionSpec spec,
                                                      InvocationRequest request,
                                                      String idempotencyKey,
                                                      String traceId,
                                                      InvocationKind kind) {
        String executionId = newExecutionId();
        InvocationTask task = new InvocationTask(
                executionId,
                functionName,
                spec,
                request,
                idempotencyKey,
                traceId,
                Instant.now(),
                1,
                kind
        );
        return new ExecutionRecord(executionId, task);
    }

    /**
     * UUIDv4-shaped id from ThreadLocalRandom. Execution ids need uniqueness, not
     * unpredictability; this avoids the SecureRandom contention of UUID.randomUUID()
     * on the invocation hot path.
     */
    static String newExecutionId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long msb = (random.nextLong() & 0xFFFF_FFFF_FFFF_0FFFL) | 0x0000_0000_0000_4000L; // version 4
        long lsb = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L; // IETF variant
        return new UUID(msb, lsb).toString();
    }

    private static void parkPendingClaim() {
        java.util.concurrent.locks.LockSupport.parkNanos(java.time.Duration.ofMillis(1).toNanos());
        if (Thread.currentThread().isInterrupted()) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted while waiting for idempotency claim");
        }
    }

    public static final class ExecutionLookup {
        private final ExecutionRecord executionRecord;
        private final boolean isNew;
        private final ExecutionStore executionStore;
        private final IdempotencyStore idempotencyStore;
        private final String functionName;
        private final String idempotencyKey;
        private final String claimToken;
        private final Outcome settledOutcome;
        private final String settledExecutionId;
        private boolean claimPublished;

        private ExecutionLookup(ExecutionRecord executionRecord,
                                boolean isNew,
                                ExecutionStore executionStore,
                                IdempotencyStore idempotencyStore,
                                String functionName,
                                String idempotencyKey,
                                String claimToken) {
            this(executionRecord, isNew, executionStore, idempotencyStore, functionName,
                    idempotencyKey, claimToken, null, null);
        }

        // Nine fields of one immutable lookup result, set once and read as a whole;
        // a parameter object here would be this class under another name.
        @SuppressWarnings("java:S107")
        private ExecutionLookup(ExecutionRecord executionRecord,
                                boolean isNew,
                                ExecutionStore executionStore,
                                IdempotencyStore idempotencyStore,
                                String functionName,
                                String idempotencyKey,
                                String claimToken,
                                Outcome settledOutcome,
                                String settledExecutionId) {
            this.executionRecord = executionRecord;
            this.isNew = isNew;
            this.executionStore = executionStore;
            this.idempotencyStore = idempotencyStore;
            this.functionName = functionName;
            this.idempotencyKey = idempotencyKey;
            this.claimToken = claimToken;
            this.settledOutcome = settledOutcome;
            this.settledExecutionId = settledExecutionId;
        }

        private static ExecutionLookup existing(ExecutionRecord executionRecord) {
            return new ExecutionLookup(executionRecord, false, null, null, null, null, null);
        }

        /** Un'esecuzione con chiave gia' finita: c'e' solo l'esito da riconsegnare. */
        private static ExecutionLookup settled(String executionId, Outcome outcome) {
            return new ExecutionLookup(null, false, null, null, null, null, null, outcome, executionId);
        }

        private static ExecutionLookup newUnclaimed(ExecutionRecord executionRecord, ExecutionStore executionStore) {
            return new ExecutionLookup(executionRecord, true, executionStore, null, null, null, null);
        }

        private static ExecutionLookup newClaimed(ExecutionRecord executionRecord,
                                                  ExecutionStore executionStore,
                                                  IdempotencyStore idempotencyStore,
                                                  String functionName,
                                                  String idempotencyKey,
                                                  String claimToken) {
            return new ExecutionLookup(executionRecord, true, executionStore, idempotencyStore, functionName, idempotencyKey, claimToken);
        }

        public ExecutionRecord executionRecord() {
            return executionRecord;
        }

        /** Non null solo quando la chiave ha trovato un'esecuzione gia' archiviata. */
        public Outcome settledOutcome() {
            return settledOutcome;
        }

        public String settledExecutionId() {
            return settledExecutionId;
        }

        public boolean isNew() {
            return isNew;
        }

        public void publishAdmission() {
            if (idempotencyStore == null || claimPublished) {
                return;
            }
            idempotencyStore.publishClaim(functionName, idempotencyKey, claimToken, executionRecord.executionId());
            claimPublished = true;
        }

        public void abandonAdmission() {
            if (!isNew) {
                return;
            }
            executionStore.remove(executionRecord.executionId());
            if (idempotencyStore != null && !claimPublished) {
                idempotencyStore.abandonClaim(functionName, idempotencyKey, claimToken);
            }
        }
    }
}
