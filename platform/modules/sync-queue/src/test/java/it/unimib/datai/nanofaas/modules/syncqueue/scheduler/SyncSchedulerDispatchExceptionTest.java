package it.unimib.datai.nanofaas.modules.syncqueue.scheduler;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.modules.syncqueue.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueMetrics;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueService;
import it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueInvocationEnqueuer;
import it.unimib.datai.nanofaas.workloadmetrics.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadDiagnostics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class SyncSchedulerDispatchExceptionTest {

    @Test
    void dispatchException_releasesSlot() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InvocationEnqueuer enqueuer = mock(InvocationEnqueuer.class);
        FunctionSpec spec = new FunctionSpec(
                "fn", "image", null, Map.of(), null,
                1000, 1, 10, 3, null, ExecutionMode.LOCAL, null, null, null
        );
        when(enqueuer.hasAvailableSlot("fn")).thenReturn(true);
        when(enqueuer.tryAcquireSlot("fn")).thenReturn(true);

        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        SyncQueueMetrics metrics = new SyncQueueMetrics(registry);
        SyncQueueConfigSource configSource = SyncQueueConfigSource.fixed(props.runtimeDefaults());
        SyncQueueService queue = new SyncQueueService(props, store, metrics, configSource);

        InvocationTask task = new InvocationTask(
                "e1", "fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        ,
        InvocationKind.SYNC
    );
        store.put(new ExecutionRecord("e1", task));
        queue.enqueueOrThrow(task);

        // Dispatch that throws an exception
        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, t -> {
            throw new RuntimeException("dispatch failed");
        });

        scheduler.tickOnce();

        verify(enqueuer).releaseDispatchSlot("fn");
    }

    @Test
    void dispatchSuccess_doesNotReleaseSlot() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InvocationEnqueuer enqueuer = mock(InvocationEnqueuer.class);
        FunctionSpec spec = new FunctionSpec(
                "fn", "image", null, Map.of(), null,
                1000, 1, 10, 3, null, ExecutionMode.LOCAL, null, null, null
        );
        when(enqueuer.hasAvailableSlot("fn")).thenReturn(true);
        when(enqueuer.tryAcquireSlot("fn")).thenReturn(true);

        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        SyncQueueMetrics metrics = new SyncQueueMetrics(registry);
        SyncQueueConfigSource configSource = SyncQueueConfigSource.fixed(props.runtimeDefaults());
        SyncQueueService queue = new SyncQueueService(props, store, metrics, configSource);

        InvocationTask task = new InvocationTask(
                "e1", "fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        ,
        InvocationKind.SYNC
    );
        store.put(new ExecutionRecord("e1", task));
        queue.enqueueOrThrow(task);

        // Dispatch that succeeds (no exception)
        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, t -> { /* success */ });

        scheduler.tickOnce();

        verify(enqueuer, never()).releaseDispatchSlot("fn");
    }

    @Test
    void realDispatchFailure_releasesCapacitySlot() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        WorkloadDiagnostics diagnostics = new WorkloadDiagnostics(registry);
        SyncQueueInvocationEnqueuer enqueuer = new SyncQueueInvocationEnqueuer(capacity, diagnostics);
        FunctionSpec spec = new FunctionSpec(
                "fn", "image", null, Map.of(), null,
                1000, 1, 10, 3, null, ExecutionMode.LOCAL, null, null, null
        );
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        SyncQueueService queue = new SyncQueueService(props, store, new SyncQueueMetrics(registry),
                SyncQueueConfigSource.fixed(props.runtimeDefaults()), capacity, diagnostics);
        queue.registerFunction("fn", 1);
        diagnostics.registerFunction("fn");

        InvocationTask task = new InvocationTask(
                "e1", "fn", spec, new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1, InvocationKind.SYNC);
        store.put(new ExecutionRecord("e1", task));
        queue.enqueueOrThrow(task);

        new SyncScheduler(enqueuer, queue, ignored -> {
            throw new RuntimeException("dispatch failed");
        }).tickOnce();

        assertEquals(0, capacity.inFlight("fn"));
    }
}
