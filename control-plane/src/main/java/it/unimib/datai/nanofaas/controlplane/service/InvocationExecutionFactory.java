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
            return new ExecutionLookup(record, true);
        }

        while (true) {
            AcquireResult acquire = idempotencyStore.acquireOrGet(functionName, idempotencyKey);
            if (acquire.state() == AcquireResult.State.CLAIMED) {
                return new ExecutionLookup(publishClaimedRecord(
                        functionName,
                        spec,
                        request,
                        idempotencyKey,
                        traceId,
                        acquire.executionIdOrToken()
                ), true);
            }
            if (acquire.state() == AcquireResult.State.PENDING) {
                Thread.onSpinWait();
                continue;
            }

            String existingExecutionId = acquire.executionIdOrToken();
            ExecutionRecord existing = executionStore.getOrNull(existingExecutionId);
            if (existing != null) {
                return new ExecutionLookup(existing, false);
            }

            AcquireResult staleClaim = idempotencyStore.claimIfMatches(functionName, idempotencyKey, existingExecutionId);
            if (staleClaim.state() == AcquireResult.State.CLAIMED) {
                return new ExecutionLookup(publishClaimedRecord(
                        functionName,
                        spec,
                        request,
                        idempotencyKey,
                        traceId,
                        staleClaim.executionIdOrToken()
                ), true);
            }
            if (staleClaim.state() == AcquireResult.State.PENDING) {
                Thread.onSpinWait();
            }
        }
    }

    private ExecutionRecord publishClaimedRecord(String functionName,
                                                 FunctionSpec spec,
                                                 InvocationRequest request,
                                                 String idempotencyKey,
                                                 String traceId,
                                                 String claimToken) {
        ExecutionRecord record = newExecutionRecord(functionName, spec, request, idempotencyKey, traceId);
        try {
            executionStore.put(record);
            idempotencyStore.publishClaim(functionName, idempotencyKey, claimToken, record.executionId());
            return record;
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

    public record ExecutionLookup(ExecutionRecord record, boolean isNew) {
    }
}
