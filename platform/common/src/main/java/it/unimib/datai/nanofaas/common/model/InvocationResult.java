package it.unimib.datai.nanofaas.common.model;

import java.util.Map;

public record InvocationResult(
        boolean success,
        Object output,
        ErrorInfo error,
        Integer statusCode,
        Map<String, String> headers,
        String encoding
) {
    public InvocationResult(boolean success, Object output, ErrorInfo error) {
        this(success, output, error, null, null, null);
    }

    public static InvocationResult success(Object output) {
        return new InvocationResult(true, output, null);
    }

    public static InvocationResult successWithEnvelope(Object output, Integer statusCode,
                                                         Map<String, String> headers, String encoding) {
        return new InvocationResult(true, output, null, statusCode, headers, encoding);
    }

    public static InvocationResult error(String code, String message) {
        return new InvocationResult(false, null, new ErrorInfo(code, message));
    }
}
