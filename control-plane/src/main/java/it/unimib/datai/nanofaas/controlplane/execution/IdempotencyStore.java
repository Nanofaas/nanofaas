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
        return Optional.of(stored.executionId());
    }

    public void put(String functionName, String key, String executionId) {
        keys.put(compose(functionName, key), new StoredKey(executionId, Instant.now()));
    }

    /**
     * Atomically stores the execution ID only if no valid (non-expired) mapping exists.
     * @return the existing execution ID if present and not expired, or null if the new value was stored
     */
    public String putIfAbsent(String functionName, String key, String executionId) {
        String composed = compose(functionName, key);
        while (true) {
            StoredKey newKey = new StoredKey(executionId, Instant.now());
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
            StoredKey newKey = new StoredKey(newExecutionId, Instant.now());
            if (keys.replace(composed, existing, newKey)) {
                return true;
            }
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

    private boolean isExpired(StoredKey stored, Instant now) {
        return stored.storedAt().plus(ttl).isBefore(now);
    }

    @PreDestroy
    public void shutdown() {
        janitor.shutdownNow();
    }

    private record StoredKey(String executionId, Instant storedAt) {
    }
}
