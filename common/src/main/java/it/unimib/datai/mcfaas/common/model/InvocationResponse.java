package com.mcfaas.common.model;

public record InvocationResponse(
        String executionId,
        String status,
        Object output,
        ErrorInfo error
) {
}
