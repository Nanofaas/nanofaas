package it.unimib.datai.nanofaas.controlplane.scheduler;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.queue.QueueManager;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueMetrics;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SyncSchedulerDispatchExceptionTest {

    @Test
    void dispatchException_releasesSlot() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueManager queueManager = new QueueManager(registry);
        FunctionSpec spec = new FunctionSpec(
                "fn", "image", null, Map.of(), null,
                1000, 1, 10, 3, null, ExecutionMode.LOCAL, null, null, null
        );
        queueManager.getOrCreate(spec);

        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        SyncQueueMetrics metrics = new SyncQueueMetrics(registry);
        SyncQueueService queue = new SyncQueueService(props, store, metrics);

        InvocationTask task = new InvocationTask(
                "e1", "fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        );
        store.put(new ExecutionRecord("e1", task));
        queue.enqueueOrThrow(task);

        // Dispatch that throws an exception
        SyncScheduler scheduler = new SyncScheduler(queueManager, queue, t -> {
            throw new RuntimeException("dispatch failed");
        });

        // Before tick: no slots acquired
        assertThat(queueManager.get("fn").inFlight()).isEqualTo(0);

        scheduler.tickOnce();

        // After tick with exception: slot should have been released back to 0
        assertThat(queueManager.get("fn").inFlight()).isEqualTo(0);
    }

    @Test
    void dispatchSuccess_doesNotReleaseSlot() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueManager queueManager = new QueueManager(registry);
        FunctionSpec spec = new FunctionSpec(
                "fn", "image", null, Map.of(), null,
                1000, 1, 10, 3, null, ExecutionMode.LOCAL, null, null, null
        );
        queueManager.getOrCreate(spec);

        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        SyncQueueMetrics metrics = new SyncQueueMetrics(registry);
        SyncQueueService queue = new SyncQueueService(props, store, metrics);

        InvocationTask task = new InvocationTask(
                "e1", "fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        );
        store.put(new ExecutionRecord("e1", task));
        queue.enqueueOrThrow(task);

        // Dispatch that succeeds (no exception)
        SyncScheduler scheduler = new SyncScheduler(queueManager, queue, t -> { /* success */ });

        scheduler.tickOnce();

        // Slot should remain acquired (inFlight = 1) since dispatch succeeded
        assertThat(queueManager.get("fn").inFlight()).isEqualTo(1);
    }
}
