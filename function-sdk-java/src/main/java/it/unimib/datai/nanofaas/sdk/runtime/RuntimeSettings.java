package it.unimib.datai.nanofaas.sdk.runtime;

/**
 * Normalized runtime inputs for a function container.
 *
 * <p>The SDK reads these values from the process environment at startup and uses them to resolve
 * request context, handler selection, and callback delivery. In warm mode the control plane may
 * provide execution and trace identifiers per request; in one-shot mode the same values must be
 * present in the environment before the Spring context starts.</p>
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
