package it.unimib.datai.nanofaas.modules.syncqueue.sync;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.execution.admission.WaitEstimator;
import it.unimib.datai.nanofaas.modules.syncqueue.config.SyncQueueProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QueueExpiryOutcomeTest {
    @Test
    void queueDeadlinePublishesTheSameErrorToWaitersAndPolling() {
        Instant now = Instant.parse("2026-09-11T12:00:00Z");
        SyncQueueProperties props = new SyncQueueProperties(true, false, 10,
                Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3);
        ExecutionStore store = new ExecutionStore();
        SyncQueueService queue = new SyncQueueService(props, store,
                new WaitEstimator(Duration.ofSeconds(30), 3),
                new SyncQueueMetrics(new SimpleMeterRegistry()), Clock.fixed(now, ZoneOffset.UTC),
                SyncQueueConfigSource.fixed(props.runtimeDefaults()),
                new FunctionCapacityRegistry(), null);
        FunctionSpec spec = new FunctionSpec("expiry", "image", null, Map.of(), null,
                1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        InvocationTask task = new InvocationTask("expiry-1", "expiry", spec,
                new InvocationRequest("input", Map.of()), null, null, now, 1, InvocationKind.SYNC);
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        store.put(record);
        queue.enqueueOrThrow(task);

        assertThat(queue.peekReady(now.plusSeconds(3))).isNull();

        assertThat(record.completion().join().error().code()).isEqualTo("QUEUE_TIMEOUT");
        assertThat(store.outcomeOf(task.executionId()).state()).isEqualTo(ExecutionState.TIMEOUT);
        assertThat(store.outcomeOf(task.executionId()).error()).isEqualTo(record.completion().join().error());
        assertThat(store.getOrNull(task.executionId())).isNull();
    }
}
