package it.unimib.datai.nanofaas.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * TTLs for the in-memory execution store.
 *
 * <p>{@code ttl}: retention of terminal executions (status queryable window).
 * {@code cleanupTtl}: when heavy payloads of terminal executions are released.
 * {@code maxLifetime}: absolute cap after which even non-terminal (stuck) executions
 * are evicted to prevent unbounded growth.</p>
 */
@ConfigurationProperties(prefix = "nanofaas.execution-store")
public record ExecutionStoreProperties(
        Duration ttl,
        Duration cleanupTtl,
        Duration maxLifetime
) {
    public ExecutionStoreProperties {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofMinutes(5);
        }
        if (cleanupTtl == null || cleanupTtl.isNegative() || cleanupTtl.isZero()) {
            cleanupTtl = Duration.ofMinutes(2);
        }
        if (maxLifetime == null || maxLifetime.isNegative() || maxLifetime.isZero()) {
            maxLifetime = Duration.ofMinutes(30);
        }
    }
}
