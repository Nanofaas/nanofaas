package it.unimib.datai.nanofaas.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How long the store remembers, and how much of it.
 *
 * <p>{@code ttl}: retention of an outcome someone can still read - an asynchronous
 * caller polling by id, or a retry replaying an idempotency key. {@code syncTtl}:
 * retention of the outcome of a keyless synchronous execution, whose response has
 * already gone back on the caller's connection. Short, because at steady state the
 * store holds `retention x admission rate`: measured on 2026-08-23 that was 270,000
 * records and 1.05 GB of live data against a 1,002 MB tenured generation, which kept
 * the collector permanently at the limit - 50.6% of the time in GC, pauses of
 * 2.851 s, and a liveness probe missed three times in a row. Not zero:
 * {@code X-Execution-Id} comes back on synchronous responses too, so
 * {@code GET /v1/executions/{id}} is a promise made to those callers as well.
 *
 * <p>{@code maxOutcomeBytes}: the cap in BYTES, which is what actually matters.
 * This is the only bound the store enforces, with Caffeine's {@code maximumWeight};
 * there is no separate count cap. {@code maxOutcomes} is used only to DERIVE the
 * default byte budget ({@code maxOutcomes x COMPACT_OUTCOME_BYTES}) when
 * {@code max-outcome-bytes} is not set explicitly, and is otherwise ignored. A
 * *readable* outcome - ASYNC or idempotency-keyed - retains the caller's payload:
 * measured, 20,000 outcomes at 64 KB occupy 1.28 GB, and a count-only budget would
 * not prevent that. An outcome's weight is estimated once at insertion, never by
 * re-serializing the payload on each access.
 *
 * <p>The default is {@code maxOutcomes x 116 bytes}: at the limit it costs what it
 * cost before, so nothing changes for compact outcomes, while large payloads are
 * evicted by weight instead of accumulating. Because the weight is an estimate, not
 * a measurement, the default must not be read as an exact heap bound.
 *
 * <p>{@code maxLifetime}: the absolute ceiling past which even a non-terminal (stuck)
 * execution is evicted, so it cannot grow without end.
 */
@ConfigurationProperties(prefix = "nanofaas.execution-store")
public record ExecutionStoreProperties(
        Duration ttl,
        Duration maxLifetime,
        Duration syncTtl,
        long maxOutcomes,
        long maxKeys,
        long maxOutcomeBytes
) {
    private static final long DEFAULT_MAX_OUTCOMES = 100_000;
    private static final long DEFAULT_MAX_KEYS = 100_000;
    /** A compact outcome's weight, the constant the default byte budget is derived from. */
    public static final long COMPACT_OUTCOME_BYTES = 116;

    /**
     * A factory, not a constructor: with two constructors Spring stops binding the
     * record by constructor and looks for a no-argument one, which a record does not have.
     */
    public static ExecutionStoreProperties of(Duration ttl, Duration maxLifetime, Duration syncTtl) {
        return new ExecutionStoreProperties(ttl, maxLifetime, syncTtl, DEFAULT_MAX_OUTCOMES, DEFAULT_MAX_KEYS, 0);
    }

    /** A factory, not a constructor: for tests that build the store with the outcome cap alone. */
    public static ExecutionStoreProperties of(Duration ttl, Duration maxLifetime, Duration syncTtl, long maxOutcomes) {
        return new ExecutionStoreProperties(ttl, maxLifetime, syncTtl, maxOutcomes, DEFAULT_MAX_KEYS, 0);
    }

    public ExecutionStoreProperties {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofMinutes(5);
        }
        if (maxLifetime == null || maxLifetime.isNegative() || maxLifetime.isZero()) {
            maxLifetime = Duration.ofMinutes(30);
        }
        if (syncTtl == null || syncTtl.isNegative() || syncTtl.isZero()) {
            syncTtl = Duration.ofSeconds(30);
        }
        if (maxOutcomes <= 0) {
            maxOutcomes = DEFAULT_MAX_OUTCOMES;
        }
        if (maxKeys <= 0) {
            maxKeys = DEFAULT_MAX_KEYS;
        }
        if (maxOutcomeBytes <= 0) {
            maxOutcomeBytes = maxOutcomes * COMPACT_OUTCOME_BYTES;
        }
        // There is no point keeping what nobody can read for any longer.
        if (syncTtl.compareTo(ttl) > 0) {
            syncTtl = ttl;
        }
    }
}
