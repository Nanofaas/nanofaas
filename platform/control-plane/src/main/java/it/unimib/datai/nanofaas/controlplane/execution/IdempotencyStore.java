package it.unimib.datai.nanofaas.controlplane.execution;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;

@Component
public class IdempotencyStore {
    private final Cache<String, StoredKey> cache;
    private final ConcurrentMap<String, StoredKey> keys;

    public IdempotencyStore() {
        this(keyLifetime(new ExecutionStoreProperties(null, null, null)));
    }

    /**
     * The key's lifetime is derived, never configured on its own.
     *
     * A key is only useful while the answer it points at still exists: an expired
     * key reads as "never seen", so a retry builds a second execution while the
     * first is still in the store, and the function runs twice. Silently - no
     * error, no log, one duplicate side effect, which is the exact failure the key
     * exists to prevent.
     *
     * Before this, the key held five minutes hardcoded here while the executions
     * held whatever nanofaas.execution-store.* said. They agreed by coincidence,
     * and raising the execution retention - a documented knob, and a reasonable
     * thing to want - broke idempotency without touching it.
     */
    @Autowired
    public IdempotencyStore(ExecutionStoreProperties executions, MeterRegistry registry) {
        this(keyLifetime(executions));
        // Whether keys are released on schedule is otherwise invisible until the heap
        // says so: a run at 843 requests a second with 5% of them keyed files roughly
        // 19,000. A supplier gauge, read at scrape time, nothing on the invocation path.
        Gauge.builder("idempotency_keys_held", this::size).register(registry);
    }

    static Duration keyLifetime(ExecutionStoreProperties executions) {
        // A terminal execution is held `ttl` past completion, a stuck one
        // `maxLifetime` past creation. The key has to outlive whichever of the two
        // kept the record it points at.
        Duration longest = executions.ttl().compareTo(executions.maxLifetime()) >= 0
                ? executions.ttl()
                : executions.maxLifetime();
        // And it must cover the platform's own retry policy, which can legitimately
        // keep one invocation in flight for timeoutMs x (maxRetries + 1).
        return longest.compareTo(MINIMUM_KEY_LIFETIME) >= 0 ? longest : MINIMUM_KEY_LIFETIME;
    }

    /** Two minutes: the default 30s timeout across the default three retries plus the first try. */
    private static final Duration MINIMUM_KEY_LIFETIME = Duration.ofMinutes(2);

    public IdempotencyStore(Duration ttl) {
        this(ttl, Ticker.systemTicker());
    }

    IdempotencyStore(Duration ttl, Ticker ticker) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .ticker(ticker)
                .build();
        this.keys = cache.asMap();
    }

    public Optional<String> getExecutionId(String functionName, String key) {
        StoredKey stored = keys.get(compose(functionName, key));
        if (stored == null || stored.pending()) {
            return Optional.empty();
        }
        return Optional.of(stored.executionId());
    }

    public void put(String functionName, String key, String executionId) {
        keys.put(compose(functionName, key), StoredKey.published(executionId, Instant.now()));
    }

    public AcquireResult acquireOrGet(String functionName, String key) {
        String composed = compose(functionName, key);
        while (true) {
            StoredKey existing = keys.get(composed);
            if (existing == null) {
                String token = pendingToken();
                StoredKey pending = StoredKey.pending(token, Instant.now());
                if (keys.putIfAbsent(composed, pending) == null) {
                    return AcquireResult.claimed(token);
                }
                continue;
            }
            if (existing.pending()) {
                return AcquireResult.pending();
            }
            return AcquireResult.existing(existing.executionId());
        }
    }

    public AcquireResult claimIfMatches(String functionName, String key, String expectedExecutionId) {
        String composed = compose(functionName, key);
        while (true) {
            StoredKey existing = keys.get(composed);
            if (existing == null) {
                return AcquireResult.missing();
            }
            if (existing.pending()) {
                return AcquireResult.pending();
            }
            if (!existing.executionId().equals(expectedExecutionId)) {
                return AcquireResult.existing(existing.executionId());
            }
            String token = pendingToken();
            StoredKey pending = StoredKey.pending(token, Instant.now());
            if (keys.replace(composed, existing, pending)) {
                return AcquireResult.claimed(token);
            }
        }
    }

    public void publishClaim(String functionName, String key, String claimToken, String executionId) {
        String composed = compose(functionName, key);
        while (true) {
            StoredKey existing = keys.get(composed);
            if (existing == null || !existing.pending() || !existing.executionId().equals(claimToken)) {
                throw new IllegalStateException("Missing idempotency claim for " + composed);
            }
            StoredKey published = StoredKey.published(executionId, Instant.now());
            if (keys.replace(composed, existing, published)) {
                return;
            }
        }
    }

    public void abandonClaim(String functionName, String key, String claimToken) {
        String composed = compose(functionName, key);
        StoredKey existing = keys.get(composed);
        if (existing != null && existing.pending() && existing.executionId().equals(claimToken)) {
            keys.remove(composed, existing);
        }
    }

    public int size() {
        cache.cleanUp();
        return keys.size();
    }

    private String compose(String functionName, String key) {
        return functionName + ":" + key;
    }

    private String pendingToken() {
        return "pending:" + Instant.now().toEpochMilli() + ":" + System.nanoTime();
    }

    public record AcquireResult(State state, String executionIdOrToken) {
        static AcquireResult claimed(String token) {
            return new AcquireResult(State.CLAIMED, token);
        }

        static AcquireResult existing(String executionId) {
            return new AcquireResult(State.EXISTING, executionId);
        }

        static AcquireResult pending() {
            return new AcquireResult(State.PENDING, null);
        }

        static AcquireResult missing() {
            return new AcquireResult(State.MISSING, null);
        }

        public enum State {
            CLAIMED,
            EXISTING,
            PENDING,
            MISSING
        }
    }

    private record StoredKey(String executionId, Instant storedAt, boolean pending) {
        static StoredKey pending(String claimToken, Instant storedAt) {
            return new StoredKey(claimToken, storedAt, true);
        }

        static StoredKey published(String executionId, Instant storedAt) {
            return new StoredKey(executionId, storedAt, false);
        }
    }
}
