package it.unimib.datai.nanofaas.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * TTLs for the in-memory execution store.
 *
 * <p>{@code ttl}: retention of terminal executions someone can still read - an
 * async caller polling by id, or a retry replaying an idempotency key.
 * {@code syncTtl}: retention of a finished synchronous execution that carries no
 * key, whose answer went back on the caller's own connection. Short, because at
 * steady state the store holds `retention x admission rate`: measured on
 * 2026-08-23 that was 270,000 records and 1.05 GB of live data against a 1,002 MB
 * tenured generation, which put the collector permanently at its limit - 50.6% of
 * wall time in GC, pauses of 2.851 s, and a liveness probe missed three times
 * running. Not zero: `X-Execution-Id` comes back on synchronous responses too, so
 * `GET /v1/executions/{id}` is a promise made to those callers as well.
 * {@code cleanupTtl}: when heavy payloads of terminal executions are released.
 * {@code maxLifetime}: absolute cap after which even non-terminal (stuck) executions
 * are evicted to prevent unbounded growth.</p>
 */
@ConfigurationProperties(prefix = "nanofaas.execution-store")
public record ExecutionStoreProperties(
        Duration ttl,
        Duration cleanupTtl,
        Duration maxLifetime,
        Duration syncTtl
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
        if (syncTtl == null || syncTtl.isNegative() || syncTtl.isZero()) {
            syncTtl = Duration.ofSeconds(30);
        }
        // Non ha senso tenere piu' a lungo cio' che nessuno puo' leggere.
        if (syncTtl.compareTo(ttl) > 0) {
            syncTtl = ttl;
        }
    }
}
