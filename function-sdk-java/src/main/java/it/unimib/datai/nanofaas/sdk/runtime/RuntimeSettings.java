package it.unimib.datai.nanofaas.sdk.runtime;

/**
 * Normalized startup configuration for the Java function runtime.
 *
 * <p>This record exists because several runtime components need the same env-driven values:
 * execution id, trace id, callback URL, and explicit handler selection. Normalizing blank strings
 * to {@code null} keeps downstream component checks consistent and avoids duplicating env parsing
 * logic across controllers, filters, and dispatchers.</p>
 *
 * <p>Lifecycle boundary: these values are fixed at application startup. They reflect the runtime
 * container's startup contract, not per-request state.</p>
 */
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
