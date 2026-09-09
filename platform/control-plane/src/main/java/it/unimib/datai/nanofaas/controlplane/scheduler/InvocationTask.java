package it.unimib.datai.nanofaas.controlplane.scheduler;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;

import java.time.Instant;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchLease;
import it.unimib.datai.nanofaas.controlplane.capacity.QueuedInputLease;

public record InvocationTask(
        String executionId,
        String functionName,
        FunctionSpec functionSpec,
        InvocationRequest request,
        String idempotencyKey,
        String traceId,
        Instant enqueuedAt,
        int attempt,
        InvocationKind kind,
        DispatchLease dispatchLease,
        QueuedInputLease queuedInputLease
) {
    public InvocationTask(String executionId, String functionName, FunctionSpec functionSpec,
                          InvocationRequest request, String idempotencyKey, String traceId,
                          Instant enqueuedAt, int attempt, InvocationKind kind) {
        this(executionId, functionName, functionSpec, request, idempotencyKey, traceId,
                enqueuedAt, attempt, kind, null, null);
    }

    public InvocationTask(String executionId, String functionName, FunctionSpec functionSpec,
                          InvocationRequest request, String idempotencyKey, String traceId,
                          Instant enqueuedAt, int attempt, InvocationKind kind,
                          DispatchLease dispatchLease) {
        this(executionId, functionName, functionSpec, request, idempotencyKey, traceId,
                enqueuedAt, attempt, kind, dispatchLease, null);
    }

    public InvocationTask withDispatchLease(DispatchLease lease) {
        return new InvocationTask(executionId, functionName, functionSpec, request,
                idempotencyKey, traceId, enqueuedAt, attempt, kind, lease, queuedInputLease);
    }

    public InvocationTask withQueuedInputLease(QueuedInputLease lease) {
        return new InvocationTask(executionId, functionName, functionSpec, request,
                idempotencyKey, traceId, enqueuedAt, attempt, kind, dispatchLease, lease);
    }

    public void releaseQueuedInput() {
        if (queuedInputLease != null) queuedInputLease.close();
    }
}
