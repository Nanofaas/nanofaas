package it.unimib.datai.nanofaas.sdk.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record RuntimeSettings(
        @Value("${EXECUTION_ID:}") String executionId,
        @Value("${TRACE_ID:}") String traceId,
        @Value("${CALLBACK_URL:}") String callbackUrl,
        @Value("${FUNCTION_HANDLER:}") String functionHandler) {

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
