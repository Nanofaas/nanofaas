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
        // When an execution archives, its key moves to the terminal binding: terminal
        // retention starts at completion, not at publication. The record still carries
        // the original key even after an internal retry.
        executionStore.onTerminal(record -> {
            if (record.idempotencyKey() != null) {
                idempotencyStore.markTerminal(record.task().functionName(), record.idempotencyKey(),
                        record.executionId());
            }
        });
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
            if (acquire.state() == AcquireResult.State.BUDGET_EXHAUSTED) {
                // Reject BEFORE the dispatch, without touching the keys already present.
                throw new IdempotencyBudgetExhaustedException();
            }

            String existingExecutionId = acquire.executionIdOrToken();
            ExecutionRecord existing = executionStore.getOrNull(existingExecutionId);
            if (existing != null) {
                // The key did its job: a second arrival found the first execution and will
                // wait on its result instead of running the function again.
                metrics.replayed(functionName, kind);
                return ExecutionLookup.existing(existing);
            }

            // Finished and archived. Looking only among the living would make this pass
            // for a stale claim, and the function would run a second time in silence:
            // exactly the failure the key exists to prevent.
            Outcome settledOutcome = executionStore.outcomeOf(existingExecutionId);
            if (settledOutcome != null) {
                metrics.replayed(functionName, kind);
                return ExecutionLookup.settled(existingExecutionId, settledOutcome);
            }

            // A TERMINAL binding pointing at an execution that is neither alive nor
            // archived: the execution concluded and its outcome payload was evicted for
            // capacity before the end of the window. The deduplication guarantee still
            // holds - the tombstone is the key itself - so the replay does NOT re-run the
            // function: it gets an explicit "no longer available" outcome (HTTP 410).
            if (acquire.terminal()) {
                metrics.replayed(functionName, kind);
                return ExecutionLookup.gone(existingExecutionId);
            }

            // A PUBLISHED binding pointing at an execution that vanished without ever
            // concluding (an admission abandoned after publication): a stale claim. Here
            // the key CAN become claimable again - the function never ran - so we re-claim
            // it and build a new execution. The window the tombstone closes is only the
            // terminal one above.
            AcquireResult reclaimed =
                    idempotencyStore.claimIfMatches(functionName, idempotencyKey, existingExecutionId);
            if (reclaimed.state() == AcquireResult.State.CLAIMED) {
                return createClaimedRecord(functionName, spec, request, idempotencyKey, traceId, kind,
                        reclaimed.executionIdOrToken());
            }
            if (reclaimed.state() == AcquireResult.State.PENDING) {
                parkPendingClaim();
            }
            // MISSING or EXISTING: the binding changed in the meantime (perhaps to
            // terminal) - re-read from the top of the loop.
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
        private final boolean gone;
        private boolean claimPublished;

        private ExecutionLookup(ExecutionRecord executionRecord,
                                boolean isNew,
                                ExecutionStore executionStore,
                                IdempotencyStore idempotencyStore,
                                String functionName,
                                String idempotencyKey,
                                String claimToken) {
            this(executionRecord, isNew, executionStore, idempotencyStore, functionName,
                    idempotencyKey, claimToken, null, null, false);
        }

        // Ten fields of one immutable lookup result, set once and read as a whole;
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
                                String settledExecutionId,
                                boolean gone) {
            this.executionRecord = executionRecord;
            this.isNew = isNew;
            this.executionStore = executionStore;
            this.idempotencyStore = idempotencyStore;
            this.functionName = functionName;
            this.idempotencyKey = idempotencyKey;
            this.claimToken = claimToken;
            this.settledOutcome = settledOutcome;
            this.settledExecutionId = settledExecutionId;
            this.gone = gone;
        }

        private static ExecutionLookup existing(ExecutionRecord executionRecord) {
            return new ExecutionLookup(executionRecord, false, null, null, null, null, null);
        }

        /** A keyed execution that is already over: there is only the outcome to hand back. */
        private static ExecutionLookup settled(String executionId, Outcome outcome) {
            return new ExecutionLookup(null, false, null, null, null, null, null, outcome, executionId, false);
        }

        /**
         * The key is still bound to a concluded execution, but its outcome payload was
         * evicted for capacity. The replay does not re-run the function.
         */
        private static ExecutionLookup gone(String executionId) {
            return new ExecutionLookup(null, false, null, null, null, null, null, null, executionId, true);
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

        /** Non-null only when the key found an execution that is already archived. */
        public Outcome settledOutcome() {
            return settledOutcome;
        }

        public String settledExecutionId() {
            return settledExecutionId;
        }

        /** Non-null only when the key found a concluded execution whose payload was evicted. */
        public boolean gone() {
            return gone;
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
            // Admission dispatches BEFORE publishing (InvocationEnqueueSupport.admitIfNew),
            // and with no queue module the dispatch is inline: the record may already be
            // archived by the time we get here. In that case the terminal listener has
            // already seen the key still pending and did nothing by design, so the
            // just-published binding would stay non-terminal forever - and a replay past
            // payload eviction would re-invoke the function instead of answering 410.
            // Both orderings converge on the same terminal binding this way.
            if (executionRecord.isTerminal()) {
                idempotencyStore.markTerminal(functionName, idempotencyKey, executionRecord.executionId());
            }
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
