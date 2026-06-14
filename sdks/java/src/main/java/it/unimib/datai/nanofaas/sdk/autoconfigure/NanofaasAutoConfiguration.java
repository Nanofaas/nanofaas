package it.unimib.datai.nanofaas.sdk.autoconfigure;

import it.unimib.datai.nanofaas.sdk.runtime.RuntimeSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * Auto-configures the Java SDK runtime inside a Spring Boot application.
 *
 * <p>This is the wiring layer that lets a function author depend on the SDK without manually
 * registering the runtime controllers, filters, clients, or settings. The runtime is driven by
 * environment variables provided by the control plane or the one-shot launcher, so the
 * configuration centralizes that startup contract in one place.</p>
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
