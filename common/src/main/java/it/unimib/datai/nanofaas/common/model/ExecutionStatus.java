package it.unimib.datai.nanofaas.common.model;

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
