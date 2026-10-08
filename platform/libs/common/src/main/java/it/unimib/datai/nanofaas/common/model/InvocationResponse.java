package it.unimib.datai.nanofaas.common.model;

import java.util.Map;

public record InvocationResponse(
        String executionId,
        String status,
        Object output,
        ErrorInfo error,
        Integer statusCode,
        Map<String, String> headers,
        String encoding
) {
    public InvocationResponse(String executionId, String status, Object output, ErrorInfo error) {
        this(executionId, status, output, error, null, null, null);
    }
}
