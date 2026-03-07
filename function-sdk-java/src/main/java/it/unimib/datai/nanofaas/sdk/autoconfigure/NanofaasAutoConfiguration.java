package it.unimib.datai.nanofaas.sdk.autoconfigure;

import it.unimib.datai.nanofaas.sdk.runtime.RuntimeSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * Auto-configuration that activates the nanofaas function runtime components.
 * Scans the SDK runtime package for controllers, filters, and clients.
 */
@AutoConfiguration
@ComponentScan("it.unimib.datai.nanofaas.sdk.runtime")
public class NanofaasAutoConfiguration {

    @Bean
    RuntimeSettings runtimeSettings(
            @Value("${EXECUTION_ID:}") String executionId,
            @Value("${TRACE_ID:}") String traceId,
            @Value("${CALLBACK_URL:}") String callbackUrl,
            @Value("${FUNCTION_HANDLER:}") String functionHandler) {
        return new RuntimeSettings(executionId, traceId, callbackUrl, functionHandler);
    }
}
