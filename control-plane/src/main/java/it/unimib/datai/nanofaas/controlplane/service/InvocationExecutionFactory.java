package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore.AcquireResult;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public final class InvocationExecutionFactory {
    private final ExecutionStore executionStore;
    private final IdempotencyStore idempotencyStore;

    public InvocationExecutionFactory(ExecutionStore executionStore, IdempotencyStore idempotencyStore) {
        this.executionStore = executionStore;
        this.idempotencyStore = idempotencyStore;
    }

    public ExecutionLookup createOrReuseExecution(String functionName,
                                                  FunctionSpec spec,
                                                  InvocationRequest request,
                                                  String idempotencyKey,
                                                  String traceId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            ExecutionRecord record = newExecutionRecord(functionName, spec, request, null, traceId);
            executionStore.put(record);
            return ExecutionLookup.newUnclaimed(record, executionStore);
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
                        acquire.executionIdOrToken()
                );
            }
            if (acquire.state() == AcquireResult.State.PENDING) {
                Thread.onSpinWait();
                continue;
            }

            String existingExecutionId = acquire.executionIdOrToken();
            ExecutionRecord existing = executionStore.getOrNull(existingExecutionId);
            if (existing != null) {
                return ExecutionLookup.existing(existing);
            }

            AcquireResult staleClaim = idempotencyStore.claimIfMatches(functionName, idempotencyKey, existingExecutionId);
            if (staleClaim.state() == AcquireResult.State.CLAIMED) {
                return createClaimedRecord(
                        functionName,
                        spec,
                        request,
                        idempotencyKey,
                        traceId,
                        staleClaim.executionIdOrToken()
                );
            }
            if (staleClaim.state() == AcquireResult.State.PENDING) {
                Thread.onSpinWait();
            }
        }
    }

    private ExecutionLookup createClaimedRecord(String functionName,
                                                FunctionSpec spec,
                                                InvocationRequest request,
                                                String idempotencyKey,
                                                String traceId,
                                                String claimToken) {
        ExecutionRecord record = newExecutionRecord(functionName, spec, request, idempotencyKey, traceId);
        try {
            executionStore.put(record);
            return ExecutionLookup.newClaimed(
                    record,
                    executionStore,
                    idempotencyStore,
                    functionName,
                    idempotencyKey,
                    claimToken
            );
        } catch (RuntimeException ex) {
            executionStore.remove(record.executionId());
            idempotencyStore.abandonClaim(functionName, idempotencyKey, claimToken);
            throw ex;
        }
    }

    private static ExecutionRecord newExecutionRecord(String functionName,
                                                      FunctionSpec spec,
                                                      InvocationRequest request,
                                                      String idempotencyKey,
                                                      String traceId) {
        String executionId = UUID.randomUUID().toString();
        InvocationTask task = new InvocationTask(
                executionId,
                functionName,
                spec,
                request,
                idempotencyKey,
                traceId,
                Instant.now(),
                1
        );
        return new ExecutionRecord(executionId, task);
    }

    public static final class ExecutionLookup {
        private final ExecutionRecord record;
        private final boolean isNew;
        private final ExecutionStore executionStore;
        private final IdempotencyStore idempotencyStore;
        private final String functionName;
        private final String idempotencyKey;
        private final String claimToken;
        private boolean claimPublished;

        private ExecutionLookup(ExecutionRecord record,
                                boolean isNew,
                                ExecutionStore executionStore,
                                IdempotencyStore idempotencyStore,
                                String functionName,
                                String idempotencyKey,
                                String claimToken) {
            this.record = record;
            this.isNew = isNew;
            this.executionStore = executionStore;
            this.idempotencyStore = idempotencyStore;
            this.functionName = functionName;
            this.idempotencyKey = idempotencyKey;
            this.claimToken = claimToken;
        }

        private static ExecutionLookup existing(ExecutionRecord record) {
            return new ExecutionLookup(record, false, null, null, null, null, null);
        }

        private static ExecutionLookup newUnclaimed(ExecutionRecord record, ExecutionStore executionStore) {
            return new ExecutionLookup(record, true, executionStore, null, null, null, null);
        }

        private static ExecutionLookup newClaimed(ExecutionRecord record,
                                                  ExecutionStore executionStore,
                                                  IdempotencyStore idempotencyStore,
                                                  String functionName,
                                                  String idempotencyKey,
                                                  String claimToken) {
            return new ExecutionLookup(record, true, executionStore, idempotencyStore, functionName, idempotencyKey, claimToken);
        }

        public ExecutionRecord record() {
            return record;
        }

        public boolean isNew() {
            return isNew;
        }

        public void publishAdmission() {
            if (idempotencyStore == null || claimPublished) {
                return;
            }
            idempotencyStore.publishClaim(functionName, idempotencyKey, claimToken, record.executionId());
            claimPublished = true;
        }

        public void abandonAdmission() {
            if (!isNew) {
                return;
            }
            executionStore.remove(record.executionId());
            if (idempotencyStore != null && !claimPublished) {
                idempotencyStore.abandonClaim(functionName, idempotencyKey, claimToken);
            }
        }
    }
}
