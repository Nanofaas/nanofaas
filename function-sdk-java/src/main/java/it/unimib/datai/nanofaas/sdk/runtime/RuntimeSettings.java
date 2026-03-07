package it.unimib.datai.nanofaas.sdk.runtime;

public record RuntimeSettings(
        String executionId,
        String traceId,
        String callbackUrl,
        String functionHandler) {

    public RuntimeSettings {
        executionId = normalize(executionId);
        traceId = normalize(traceId);
        callbackUrl = normalize(callbackUrl);
        functionHandler = normalize(functionHandler);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
