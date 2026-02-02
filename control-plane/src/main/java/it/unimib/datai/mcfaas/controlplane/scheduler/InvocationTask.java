package it.unimib.datai.mcfaas.controlplane.scheduler;

import it.unimib.datai.mcfaas.common.model.FunctionSpec;
import it.unimib.datai.mcfaas.common.model.InvocationRequest;

import java.time.Instant;

public record InvocationTask(
        String executionId,
        String functionName,
        FunctionSpec functionSpec,
        InvocationRequest request,
        String idempotencyKey,
        String traceId,
        Instant enqueuedAt,
        int attempt
) {
}
