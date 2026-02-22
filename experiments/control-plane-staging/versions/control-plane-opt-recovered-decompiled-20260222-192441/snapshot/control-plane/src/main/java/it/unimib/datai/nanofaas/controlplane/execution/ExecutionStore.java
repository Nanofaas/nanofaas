/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  jakarta.annotation.PreDestroy
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.stereotype.Component
 */
package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
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
public class ExecutionStore {
    private final Map<String, StoredExecution> executions = new ConcurrentHashMap<String, StoredExecution>();
    private final ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor();
    private final Duration cleanupTtl;
    private final Duration ttl;
    private final Duration staleTtl;
    private volatile boolean epochMillisEnabled;

    public ExecutionStore() {
        this(Duration.ofMinutes(2L), Duration.ofMinutes(5L), Duration.ofMinutes(10L), false);
    }

    ExecutionStore(boolean epochMillisEnabled) {
        this(Duration.ofMinutes(2L), Duration.ofMinutes(5L), Duration.ofMinutes(10L), epochMillisEnabled);
    }

    ExecutionStore(Duration cleanupTtl, Duration ttl, Duration staleTtl, boolean epochMillisEnabled) {
        this.cleanupTtl = cleanupTtl;
        this.ttl = ttl;
        this.staleTtl = staleTtl;
        this.epochMillisEnabled = epochMillisEnabled;
        this.janitor.scheduleAtFixedRate(this::evictExpired, 1L, 1L, TimeUnit.MINUTES);
    }

    @Value(value="${nanofaas.optimizations.epoch-millis-enabled:false}")
    void setEpochMillisEnabled(boolean epochMillisEnabled) {
        this.epochMillisEnabled = epochMillisEnabled;
    }

    public void put(ExecutionRecord record) {
        long nowMillis = System.currentTimeMillis();
        if (this.epochMillisEnabled) {
            this.executions.put(record.executionId(), new StoredExecution(record, null, nowMillis));
            return;
        }
        this.executions.put(record.executionId(), new StoredExecution(record, Instant.ofEpochMilli(nowMillis), 0L));
    }

    public Optional<ExecutionRecord> get(String executionId) {
        StoredExecution stored = this.executions.get(executionId);
        if (stored == null) {
            return Optional.empty();
        }
        return Optional.of(stored.record());
    }

    public ExecutionRecord getOrNull(String executionId) {
        StoredExecution stored = this.executions.get(executionId);
        return stored == null ? null : stored.record();
    }

    public void remove(String executionId) {
        this.executions.remove(executionId);
    }

    private void evictExpired() {
        long nowMillis = System.currentTimeMillis();
        long cutoffMillis = nowMillis - this.ttl.toMillis();
        long cleanupCutoffMillis = nowMillis - this.cleanupTtl.toMillis();
        long staleCutoffMillis = nowMillis - this.staleTtl.toMillis();
        this.executions.entrySet().removeIf(entry -> {
            ExecutionState state;
            StoredExecution stored = (StoredExecution)entry.getValue();
            long createdMillis = stored.createdAtEpochMillis(this.epochMillisEnabled);
            if (createdMillis < staleCutoffMillis) {
                return true;
            }
            if (createdMillis < cutoffMillis && (state = stored.record().state()) != ExecutionState.RUNNING && state != ExecutionState.QUEUED) {
                return true;
            }
            if (createdMillis < cleanupCutoffMillis && (state = stored.record().state()) != ExecutionState.RUNNING && state != ExecutionState.QUEUED) {
                stored.record().cleanup();
            }
            return false;
        });
    }

    @PreDestroy
    public void shutdown() {
        this.janitor.shutdownNow();
    }

    private record StoredExecution(ExecutionRecord record, Instant createdAt, long createdAtMillis) {
        long createdAtEpochMillis(boolean epochMillisEnabled) {
            if (epochMillisEnabled) {
                return this.createdAtMillis;
            }
            if (this.createdAt != null) {
                return this.createdAt.toEpochMilli();
            }
            return this.createdAtMillis;
        }
    }
}
