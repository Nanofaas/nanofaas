package it.unimib.datai.nanofaas.controlplane.execution;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;

@Component
public class IdempotencyStore {
    private final Cache<String, StoredKey> cache;
    private final ConcurrentMap<String, StoredKey> keys;

    public IdempotencyStore() {
        this(Duration.ofMinutes(5));
    }

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
