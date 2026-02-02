package com.mcfaas.controlplane.scheduler;

import com.mcfaas.common.model.FunctionSpec;
import com.mcfaas.common.model.InvocationRequest;

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
