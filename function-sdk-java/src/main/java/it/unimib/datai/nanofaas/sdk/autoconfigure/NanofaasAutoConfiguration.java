package it.unimib.datai.nanofaas.sdk.autoconfigure;

import it.unimib.datai.nanofaas.sdk.runtime.RuntimeSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * Auto-configures the nanoFaaS Java runtime inside a Spring Boot application.
 *
 * <p>This component exists so function authors only need to add the SDK dependency and annotate
 * their handler. Spring Boot imports this configuration, scans the runtime package, and wires the
 * invoke/health/metrics/callback stack without hand-written bootstrap code.</p>
 *
 * <p>Environment assumptions: the control plane or container entrypoint provides execution and
 * callback metadata through env vars. In warm mode, these settings may be supplemented or
 * overridden by request headers, but the bootstrap still starts from the same env-driven contract.</p>
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
