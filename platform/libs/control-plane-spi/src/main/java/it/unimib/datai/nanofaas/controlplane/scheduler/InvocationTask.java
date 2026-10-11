package it.unimib.datai.nanofaas.controlplane.scheduler;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;

import java.time.Instant;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
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
        DispatchOwnership dispatchLease,
        QueuedInputLease queuedInputLease,
        String replicaAdmissionEpoch
) {
    public InvocationTask {
        if (replicaAdmissionEpoch != null && (replicaAdmissionEpoch.isBlank() || replicaAdmissionEpoch.length() > 256)) {
            throw new IllegalArgumentException("bounded replica admission identity required");
        }
    }

    public InvocationTask(String executionId, String functionName, FunctionSpec functionSpec,
                          InvocationRequest request, String idempotencyKey, String traceId,
                          Instant enqueuedAt, int attempt, InvocationKind kind,
                          DispatchOwnership dispatchLease, QueuedInputLease queuedInputLease) {
        this(executionId, functionName, functionSpec, request, idempotencyKey, traceId,
                enqueuedAt, attempt, kind, dispatchLease, queuedInputLease, null);
    }
    public InvocationTask(String executionId, String functionName, FunctionSpec functionSpec,
                          InvocationRequest request, String idempotencyKey, String traceId,
                          Instant enqueuedAt, int attempt, InvocationKind kind) {
        this(executionId, functionName, functionSpec, request, idempotencyKey, traceId,
                enqueuedAt, attempt, kind, null, null);
    }

    public InvocationTask(String executionId, String functionName, FunctionSpec functionSpec,
                          InvocationRequest request, String idempotencyKey, String traceId,
                          Instant enqueuedAt, int attempt, InvocationKind kind,
                          DispatchOwnership dispatchLease) {
        this(executionId, functionName, functionSpec, request, idempotencyKey, traceId,
                enqueuedAt, attempt, kind, dispatchLease, null);
    }

    public InvocationTask withDispatchLease(DispatchOwnership lease) {
        return new InvocationTask(executionId, functionName, functionSpec, request,
                idempotencyKey, traceId, enqueuedAt, attempt, kind, lease, queuedInputLease, replicaAdmissionEpoch);
    }

    public InvocationTask withQueuedInputLease(QueuedInputLease lease) {
        return new InvocationTask(executionId, functionName, functionSpec, request,
                idempotencyKey, traceId, enqueuedAt, attempt, kind, dispatchLease, lease, replicaAdmissionEpoch);
    }

    public InvocationTask withReplicaAdmissionEpoch(String admissionEpoch) {
        return new InvocationTask(executionId, functionName, functionSpec, request,
                idempotencyKey, traceId, enqueuedAt, attempt, kind, dispatchLease, queuedInputLease, admissionEpoch);
    }

    public void releaseQueuedInput() {
        if (queuedInputLease != null) queuedInputLease.close();
    }
}
