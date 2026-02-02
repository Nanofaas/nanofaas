package it.unimib.datai.mcfaas.controlplane.execution;

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
        this(Duration.ofMinutes(15));
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

    @PreDestroy
    public void shutdown() {
        janitor.shutdownNow();
    }

    private record StoredKey(String executionId, Instant storedAt) {
    }
}
