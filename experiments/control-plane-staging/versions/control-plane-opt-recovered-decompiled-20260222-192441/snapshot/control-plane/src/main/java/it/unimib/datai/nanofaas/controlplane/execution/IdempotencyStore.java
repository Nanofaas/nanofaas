/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  jakarta.annotation.PreDestroy
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.stereotype.Component
 */
package it.unimib.datai.nanofaas.controlplane.execution;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyStore {
    private final Map<String, StoredKey> keys = new ConcurrentHashMap<String, StoredKey>();
    private final ScheduledExecutorService janitor;
    private final Duration ttl;
    private volatile boolean epochMillisEnabled;

    public IdempotencyStore() {
        this(Duration.ofMinutes(5L), false);
    }

    public IdempotencyStore(Duration ttl) {
        this(ttl, false);
    }

    IdempotencyStore(Duration ttl, boolean epochMillisEnabled) {
        this.ttl = ttl;
        this.epochMillisEnabled = epochMillisEnabled;
        this.janitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idempotency-store-janitor");
            t.setDaemon(true);
            return t;
        });
        this.janitor.scheduleAtFixedRate(this::evictExpired, 1L, 1L, TimeUnit.MINUTES);
    }

    @Value(value="${nanofaas.optimizations.epoch-millis-enabled:false}")
    void setEpochMillisEnabled(boolean epochMillisEnabled) {
        this.epochMillisEnabled = epochMillisEnabled;
    }

    public Optional<String> getExecutionId(String functionName, String key) {
        String composed = this.compose(functionName, key);
        StoredKey stored = this.keys.get(composed);
        if (stored == null) {
            return Optional.empty();
        }
        if (this.isExpired(stored, System.currentTimeMillis())) {
            this.keys.remove(composed, stored);
            return Optional.empty();
        }
        return Optional.of(stored.executionId());
    }

    public void put(String functionName, String key, String executionId) {
        this.keys.put(this.compose(functionName, key), this.newStoredKey(executionId, System.currentTimeMillis()));
    }

    public String putIfAbsent(String functionName, String key, String executionId) {
        long nowMillis;
        StoredKey newKey;
        String composed = this.compose(functionName, key);
        StoredKey existing = this.keys.putIfAbsent(composed, newKey = this.newStoredKey(executionId, nowMillis = System.currentTimeMillis()));
        if (existing == null) {
            return null;
        }
        if (this.isExpired(existing, nowMillis)) {
            this.keys.replace(composed, existing, newKey);
            return null;
        }
        return existing.executionId();
    }

    public int size() {
        return this.keys.size();
    }

    private void evictExpired() {
        long cutoffMillis = System.currentTimeMillis() - this.ttl.toMillis();
        this.keys.entrySet().removeIf(entry -> ((StoredKey)entry.getValue()).storedAtEpochMillis(this.epochMillisEnabled) < cutoffMillis);
    }

    private String compose(String functionName, String key) {
        return functionName + ":" + key;
    }

    @PreDestroy
    public void shutdown() {
        this.janitor.shutdownNow();
    }

    private boolean isExpired(StoredKey stored, long nowMillis) {
        return stored.storedAtEpochMillis(this.epochMillisEnabled) + this.ttl.toMillis() < nowMillis;
    }

    private StoredKey newStoredKey(String executionId, long nowMillis) {
        if (this.epochMillisEnabled) {
            return new StoredKey(executionId, null, nowMillis);
        }
        return new StoredKey(executionId, Instant.ofEpochMilli(nowMillis), 0L);
    }

    private record StoredKey(String executionId, Instant storedAt, long storedAtMillis) {
        long storedAtEpochMillis(boolean epochMillisEnabled) {
            if (epochMillisEnabled) {
                return this.storedAtMillis;
            }
            if (this.storedAt != null) {
                return this.storedAt.toEpochMilli();
            }
            return this.storedAtMillis;
        }
    }
}
