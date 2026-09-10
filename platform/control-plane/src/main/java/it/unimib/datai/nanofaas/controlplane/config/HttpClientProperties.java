package it.unimib.datai.nanofaas.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for HTTP client settings.
 *
 * <p>Defaults: connect timeout 5000 ms, read (response) timeout 30000 ms,
 * max in-memory codec buffer size 16 MB, 500 pooled connections per destination
 * with twice that many queued acquisitions, a 45000 ms acquisition timeout, a 30 s idle
 * lifetime, 5 s background eviction checks and disposal of empty destination pools after
 * 30 s. Maximum connection lifetime is disabled by default and can be enabled explicitly.
 *
 * <p>The pool properties make the connection budget and retention policy explicit and tunable;
 * before them the pool was whatever Reactor Netty's global defaults happened to be, shared with
 * anything else in the JVM, without finite destination retention, and never disposed.
 *
 * <p><b>The connection budget and acquisition defaults deliberately preserve the previous
 * behaviour.</b> A shorter acquisition timeout looked like an obvious win — on a saturated
 * pool an unbounded-ish
 * 45 s queue lets a request whose caller has given up still take a connection and make the
 * backend answer nobody. Measured, that was 62% of the backend's work wasted and a p95 at
 * twice the caller's budget. But the measurement did not reproduce production: {@code
 * ExternalDispatcher} wraps the call in {@code .timeout(functionTimeout)}, which cancels
 * the Mono and with it the pending acquisition. Re-measured with that cancellation, a 45 s,
 * a 5 s and a 1 s acquisition timeout are indistinguishable — same useful throughput, same
 * wasted work, same p95. The tuning was therefore NOT adopted, per the plan's rule that an
 * intervention whose benefit does not exceed baseline variability keeps the previous
 * default. See docs/experiments/control-plane-tuning-2026-09/RESULTS.md.
 *
 * <p>The property still exists because the cancellation that makes the timeout irrelevant
 * is a property of the caller, not of the pool: a future dispatch path that forgets to
 * bound its own call would inherit the 45 s wait, and an operator can then shorten it
 * without a rebuild.
 */
@ConfigurationProperties(prefix = "nanofaas.http-client")
public record HttpClientProperties(
        Integer connectTimeoutMs,
        Integer readTimeoutMs,
        Integer maxInMemorySizeMb,
        Integer maxConnections,
        Integer pendingAcquireMaxCount,
        Integer pendingAcquireTimeoutMs,
        Integer maxIdleTimeMs,
        Integer maxLifeTimeMs,
        Integer evictionIntervalMs,
        Integer inactivePoolDisposeIntervalMs,
        Integer poolInactivityMs
) {
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 30000;
    private static final int DEFAULT_MAX_IN_MEMORY_MB = 16;
    /** Reactor Netty's own defaults, kept so this change alters no behaviour it did not measure. */
    private static final int DEFAULT_MAX_CONNECTIONS = 500;
    private static final int DEFAULT_PENDING_ACQUIRE_TIMEOUT_MS = 45_000;
    private static final int DEFAULT_MAX_IDLE_TIME_MS = 30_000;
    private static final int DEFAULT_MAX_LIFE_TIME_MS = 0;
    private static final int DEFAULT_EVICTION_INTERVAL_MS = 5_000;
    private static final int DEFAULT_INACTIVE_POOL_DISPOSE_INTERVAL_MS = 5_000;
    private static final int DEFAULT_POOL_INACTIVITY_MS = 30_000;

    public HttpClientProperties {
        if (connectTimeoutMs == null || connectTimeoutMs <= 0) {
            connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
        }
        if (readTimeoutMs == null || readTimeoutMs <= 0) {
            readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
        }
        if (maxInMemorySizeMb == null || maxInMemorySizeMb <= 0) {
            maxInMemorySizeMb = DEFAULT_MAX_IN_MEMORY_MB;
        }
        if (maxConnections == null || maxConnections <= 0) {
            maxConnections = DEFAULT_MAX_CONNECTIONS;
        }
        if (pendingAcquireMaxCount == null || pendingAcquireMaxCount <= 0) {
            // Twice the connections, mirroring Reactor Netty: raising only maxConnections
            // should widen the queue with it, not leave it at an unrelated constant.
            pendingAcquireMaxCount = maxConnections * 2;
        }
        if (pendingAcquireTimeoutMs == null || pendingAcquireTimeoutMs <= 0) {
            pendingAcquireTimeoutMs = DEFAULT_PENDING_ACQUIRE_TIMEOUT_MS;
        }
        if (maxIdleTimeMs == null || maxIdleTimeMs <= 0) {
            maxIdleTimeMs = DEFAULT_MAX_IDLE_TIME_MS;
        }
        if (maxLifeTimeMs == null || maxLifeTimeMs < 0) {
            maxLifeTimeMs = DEFAULT_MAX_LIFE_TIME_MS;
        }
        if (evictionIntervalMs == null || evictionIntervalMs <= 0) {
            evictionIntervalMs = DEFAULT_EVICTION_INTERVAL_MS;
        }
        if (inactivePoolDisposeIntervalMs == null || inactivePoolDisposeIntervalMs <= 0) {
            inactivePoolDisposeIntervalMs = DEFAULT_INACTIVE_POOL_DISPOSE_INTERVAL_MS;
        }
        if (poolInactivityMs == null || poolInactivityMs <= 0) {
            poolInactivityMs = DEFAULT_POOL_INACTIVITY_MS;
        }
    }
}
