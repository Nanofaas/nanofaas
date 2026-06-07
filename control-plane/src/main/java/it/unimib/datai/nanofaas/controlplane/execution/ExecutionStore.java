package it.unimib.datai.nanofaas.controlplane.execution;

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
    private final ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor();
    private final Duration cleanupTtl = Duration.ofMinutes(2);
    private final Duration ttl = Duration.ofMinutes(5);

    public ExecutionStore() {
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

    private void evictExpired() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(ttl);
        Instant cleanupCutoff = now.minus(cleanupTtl);

        executions.entrySet().removeIf(entry -> {
            StoredExecution stored = entry.getValue();
            ExecutionRecord record = stored.record();
            Instant created = stored.createdAt();
            if (!record.isTerminal()) {
                return false;
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
