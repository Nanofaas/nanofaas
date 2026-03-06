package it.unimib.datai.nanofaas.sdk.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record RuntimeSettings(
        @Value("${EXECUTION_ID:#{systemEnvironment['EXECUTION_ID'] ?: null}}") String executionId,
        @Value("${TRACE_ID:#{systemEnvironment['TRACE_ID'] ?: null}}") String traceId,
        @Value("${CALLBACK_URL:#{systemEnvironment['CALLBACK_URL'] ?: null}}") String callbackUrl,
        @Value("${FUNCTION_HANDLER:#{systemEnvironment['FUNCTION_HANDLER'] ?: null}}") String functionHandler) {

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
