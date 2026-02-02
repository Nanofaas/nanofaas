package com.mcfaas.controlplane.registry;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mcfaas.defaults")
public record FunctionDefaults(
        int timeoutMs,
        int concurrency,
        int queueSize,
        int maxRetries
) {
}
