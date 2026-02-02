package it.unimib.datai.nanofaas.controlplane.registry;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nanofaas.defaults")
public record FunctionDefaults(
        int timeoutMs,
        int concurrency,
        int queueSize,
        int maxRetries
) {
}
