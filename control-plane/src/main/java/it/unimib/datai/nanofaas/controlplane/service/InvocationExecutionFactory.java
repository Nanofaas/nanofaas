package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
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
            ExecutionRecord record = newExecutionRecord(functionName, spec, request, idempotencyKey, traceId);
            executionStore.put(record);

            String existingExecutionId = idempotencyStore.putIfAbsent(functionName, idempotencyKey, record.executionId());
            if (existingExecutionId == null) {
                return new ExecutionLookup(record, true);
            }
            ExecutionRecord existing = executionStore.getOrNull(existingExecutionId);
            if (existing != null) {
                executionStore.remove(record.executionId());
                return new ExecutionLookup(existing, false);
            }
            if (idempotencyStore.replaceExecutionId(functionName, idempotencyKey, existingExecutionId, record.executionId())) {
                return new ExecutionLookup(record, true);
            }
            executionStore.remove(record.executionId());
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
