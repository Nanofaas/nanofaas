package it.unimib.datai.nanofaas.controlplane.execution;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class IdempotencyStore {
    private final Map<String, StoredKey> keys = new ConcurrentHashMap<>();
    private final ScheduledExecutorService janitor;
    private final Duration ttl;

    public IdempotencyStore() {
        this(Duration.ofMinutes(5));
    }

    public IdempotencyStore(Duration ttl) {
        this.ttl = ttl;
        this.janitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idempotency-store-janitor");
            t.setDaemon(true);
            return t;
        });
        janitor.scheduleAtFixedRate(this::evictExpired, 1, 1, TimeUnit.MINUTES);
    }

    public Optional<String> getExecutionId(String functionName, String key) {
        StoredKey stored = keys.get(compose(functionName, key));
        if (stored == null) {
            return Optional.empty();
        }
        // Also check TTL during lookup for immediate expiration
        if (stored.storedAt().plus(ttl).isBefore(Instant.now())) {
            keys.remove(compose(functionName, key));
            return Optional.empty();
        }
        if (stored.pending()) {
            return Optional.empty();
        }
        return Optional.of(stored.executionId());
    }

    public void put(String functionName, String key, String executionId) {
        keys.put(compose(functionName, key), StoredKey.published(executionId, Instant.now()));
    }

    /**
     * Atomically stores the execution ID only if no valid (non-expired) mapping exists.
     * @return the existing execution ID if present and not expired, or null if the new value was stored
     */
    public String putIfAbsent(String functionName, String key, String executionId) {
        String composed = compose(functionName, key);
        while (true) {
            StoredKey newKey = StoredKey.published(executionId, Instant.now());
            StoredKey existing = keys.putIfAbsent(composed, newKey);
            if (existing == null) {
                return null; // Successfully stored
            }
            if (!isExpired(existing, Instant.now())) {
                return existing.executionId();
            }
            if (keys.replace(composed, existing, newKey)) {
                return null;
            }
        }
    }

    public boolean replaceExecutionId(String functionName, String key, String expectedExecutionId, String newExecutionId) {
        String composed = compose(functionName, key);
        while (true) {
            StoredKey existing = keys.get(composed);
            if (existing == null || !existing.executionId().equals(expectedExecutionId)) {
                return false;
            }
            StoredKey newKey = StoredKey.published(newExecutionId, Instant.now());
            if (keys.replace(composed, existing, newKey)) {
                return true;
            }
        }
    }

    public AcquireResult acquireOrGet(String functionName, String key) {
        String composed = compose(functionName, key);
        while (true) {
            Instant now = Instant.now();
            StoredKey existing = keys.get(composed);
            if (existing == null) {
                String token = pendingToken();
                StoredKey pending = StoredKey.pending(token, now);
                if (keys.putIfAbsent(composed, pending) == null) {
                    return AcquireResult.claimed(token);
                }
                continue;
            }
            if (isExpired(existing, now)) {
                String token = pendingToken();
                StoredKey pending = StoredKey.pending(token, now);
                if (keys.replace(composed, existing, pending)) {
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
            Instant now = Instant.now();
            StoredKey existing = keys.get(composed);
            if (existing == null) {
                return AcquireResult.missing();
            }
            if (isExpired(existing, now)) {
                String token = pendingToken();
                StoredKey pending = StoredKey.pending(token, now);
                if (keys.replace(composed, existing, pending)) {
                    return AcquireResult.claimed(token);
                }
                continue;
            }
            if (existing.pending()) {
                return AcquireResult.pending();
            }
            if (!existing.executionId().equals(expectedExecutionId)) {
                return AcquireResult.existing(existing.executionId());
            }
            String token = pendingToken();
            StoredKey pending = StoredKey.pending(token, now);
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
        return keys.size();
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(ttl);
        keys.entrySet().removeIf(entry -> entry.getValue().storedAt().isBefore(cutoff));
    }

    private String compose(String functionName, String key) {
        return functionName + ":" + key;
    }

    private String pendingToken() {
        return "pending:" + Instant.now().toEpochMilli() + ":" + System.nanoTime();
    }

    private boolean isExpired(StoredKey stored, Instant now) {
        return stored.storedAt().plus(ttl).isBefore(now);
    }

    @PreDestroy
    public void shutdown() {
        janitor.shutdownNow();
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
