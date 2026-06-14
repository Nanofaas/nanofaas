package it.unimib.datai.nanofaas.controlplane.scheduler;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;

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
