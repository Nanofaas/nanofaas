package com.mcfaas.common.model;

public record InvocationResult(
        boolean success,
        Object output,
        ErrorInfo error
) {
    public static InvocationResult success(Object output) {
        return new InvocationResult(true, output, null);
    }

    public static InvocationResult error(String code, String message) {
        return new InvocationResult(false, null, new ErrorInfo(code, message));
    }
}
