package com.mcfaas.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for HTTP client settings.
 */
@ConfigurationProperties(prefix = "mcfaas.http-client")
public record HttpClientProperties(
        Integer connectTimeoutMs,
        Integer readTimeoutMs,
        Integer maxInMemorySizeMb
) {
    public HttpClientProperties {
        if (connectTimeoutMs == null || connectTimeoutMs <= 0) {
            connectTimeoutMs = 5000;
        }
        if (readTimeoutMs == null || readTimeoutMs <= 0) {
            readTimeoutMs = 30000;
        }
        if (maxInMemorySizeMb == null || maxInMemorySizeMb <= 0) {
            maxInMemorySizeMb = 1;
        }
    }
}
