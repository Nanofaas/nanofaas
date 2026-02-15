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
    private final Duration ttl = Duration.ofMinutes(15);
    /** Force-evict even RUNNING/QUEUED executions after this deadline to prevent unbounded growth. */
    private final Duration staleTtl = Duration.ofMinutes(30);

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

    public void remove(String executionId) {
        executions.remove(executionId);
    }

    private void evictExpired() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(ttl);
        Instant staleCutoff = now.minus(staleTtl);
        executions.entrySet().removeIf(entry -> {
            StoredExecution stored = entry.getValue();
            Instant created = stored.createdAt();
            if (created.isBefore(staleCutoff)) {
                // Force-evict everything older than staleTtl, including stuck RUNNING/QUEUED
                return true;
            }
            if (created.isBefore(cutoff)) {
                ExecutionState state = stored.record().state();
                // Don't evict active executions until staleTtl
                return state != ExecutionState.RUNNING && state != ExecutionState.QUEUED;
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
