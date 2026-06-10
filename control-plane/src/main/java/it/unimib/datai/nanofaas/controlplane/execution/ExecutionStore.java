package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;

@Component
public class ExecutionStore {
    private final Map<String, StoredExecution> executions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService janitor;
    private final Duration cleanupTtl;
    private final Duration ttl;
    private final Duration maxLifetime;

    public ExecutionStore() {
        this(new ExecutionStoreProperties(null, null, null));
    }

    // @Autowired is required: with two constructors Spring would otherwise pick the
    // no-arg one and silently ignore the configured properties.
    @Autowired
    public ExecutionStore(ExecutionStoreProperties properties) {
        this.ttl = properties.ttl();
        this.cleanupTtl = properties.cleanupTtl();
        this.maxLifetime = properties.maxLifetime();
        this.janitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "execution-store-janitor");
            t.setDaemon(true);
            return t;
        });
        janitor.scheduleAtFixedRate(this::evictExpired, 1, 1, TimeUnit.MINUTES);
    }

    public void put(ExecutionRecord record) {
        executions.put(record.executionId(), new StoredExecution(record, Instant.now()));
    }

    public Optional<ExecutionRecord> get(String executionId) {
        StoredExecution stored = executions.get(executionId);
        if (stored == null) {
            return Optional.empty();
        }
        return Optional.of(stored.record());
    }

    /**
     * Hot-path lookup without Optional allocation.
     */
    public ExecutionRecord getOrNull(String executionId) {
        StoredExecution stored = executions.get(executionId);
        return stored == null ? null : stored.record();
    }

    public void remove(String executionId) {
        executions.remove(executionId);
    }

    // Package-private for deterministic testing.
    void evictExpired() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(ttl);
        Instant cleanupCutoff = now.minus(cleanupTtl);
        Instant lifetimeCutoff = now.minus(maxLifetime);

        executions.entrySet().removeIf(entry -> {
            StoredExecution stored = entry.getValue();
            ExecutionRecord record = stored.record();
            Instant created = stored.createdAt();
            if (!record.isTerminal()) {
                // Stuck executions (lost dispatch, missing callback) must not leak forever.
                return created.isBefore(lifetimeCutoff);
            }

            Instant completedAt = record.finishedAt();
            Instant retentionAnchor = completedAt == null ? created : completedAt;

            if (retentionAnchor.isBefore(cutoff)) {
                return true;
            }
            if (retentionAnchor.isBefore(cleanupCutoff)) {
                record.cleanup();
            }
            return false;
        });
    }

    @PreDestroy
    public void shutdown() {
        janitor.shutdownNow();
    }

    private record StoredExecution(ExecutionRecord record, Instant createdAt) {
    }
}
