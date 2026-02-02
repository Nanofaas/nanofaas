package com.mcfaas.common.model;

import java.time.Instant;

public record ExecutionStatus(
        String executionId,
        String status,
        Instant startedAt,
        Instant finishedAt,
        Object output,
        ErrorInfo error
) {
}
