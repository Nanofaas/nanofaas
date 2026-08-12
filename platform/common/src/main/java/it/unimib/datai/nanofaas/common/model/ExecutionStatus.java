package it.unimib.datai.nanofaas.common.model;

import java.time.Instant;
import java.util.Map;

public record ExecutionStatus(
        String executionId,
        String status,
        Instant startedAt,
        Instant finishedAt,
        Object output,
        ErrorInfo error,
        boolean coldStart,
        Long initDurationMs,
        Integer statusCode,
        Map<String, String> headers,
        String encoding
) {
    public ExecutionStatus(String executionId, String status, Instant startedAt, Instant finishedAt,
                           Object output, ErrorInfo error, boolean coldStart, Long initDurationMs) {
        this(executionId, status, startedAt, finishedAt, output, error, coldStart, initDurationMs,
                null, null, null);
    }
}
