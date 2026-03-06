package it.unimib.datai.nanofaas.sdk.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record RuntimeSettings(
        @Value("${EXECUTION_ID:#{systemEnvironment['EXECUTION_ID'] ?: 'test-execution'}}") String executionId,
        @Value("${TRACE_ID:#{systemEnvironment['TRACE_ID'] ?: null}}") String traceId,
        @Value("${CALLBACK_URL:#{systemEnvironment['CALLBACK_URL'] ?: null}}") String callbackUrl,
        @Value("${FUNCTION_HANDLER:#{systemEnvironment['FUNCTION_HANDLER'] ?: null}}") String functionHandler) {
}
