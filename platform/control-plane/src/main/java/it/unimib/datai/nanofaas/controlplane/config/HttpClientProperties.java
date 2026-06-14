package it.unimib.datai.nanofaas.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for HTTP client settings.
 *
 * <p>Defaults: connect timeout 5000 ms, read (response) timeout 30000 ms,
 * max in-memory codec buffer size 16 MB.
 */
@ConfigurationProperties(prefix = "nanofaas.http-client")
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
            maxInMemorySizeMb = 16;
        }
    }
}
