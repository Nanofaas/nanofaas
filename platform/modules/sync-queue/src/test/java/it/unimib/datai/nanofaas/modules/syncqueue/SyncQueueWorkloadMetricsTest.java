package it.unimib.datai.nanofaas.modules.syncqueue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.modules.syncqueue.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueMetrics;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueService;
import it.unimib.datai.nanofaas.workloadmetrics.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsBinder;
import it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueInvocationEnqueuer;
import it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueWorkloadMetricsSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SyncQueueWorkloadMetricsTest {
    @Test
    void enqueuerKeepsAsyncPathDisabledAndDelegatesCapacity() {
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        registry.register("fn", 1);
        SyncQueueInvocationEnqueuer enqueuer = new SyncQueueInvocationEnqueuer(registry);

        assertFalse(enqueuer.enabled());
        assertFalse(enqueuer.enqueue(null));
        assertTrue(enqueuer.hasAvailableSlot("fn"));
        assertTrue(enqueuer.tryAcquireSlot("fn"));
        assertFalse(enqueuer.hasAvailableSlot("fn"));
        enqueuer.releaseDispatchSlot("fn");
        assertTrue(enqueuer.hasAvailableSlot("fn"));
    }

    @Test
    void sourceReportsPendingFunctionDepthAndSyncDispatchableBacklog() {
        SyncQueueService service = mock(SyncQueueService.class);
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        registry.register("fn", 1);
        when(service.queuedItems("fn")).thenReturn(2);
        SyncQueueWorkloadMetricsSource source = new SyncQueueWorkloadMetricsSource(service, registry);

        assertEquals(2, source.queueDepth("fn"));
        assertEquals(2, source.dispatchableBacklog("fn"));
        assertEquals(1, source.effectiveConcurrency("fn"));
        assertEquals(0, source.inFlight("fn"));

        assertTrue(registry.tryAcquireSlot("fn"));
        assertEquals(0, source.dispatchableBacklog("fn"));
    }

    @Test
    void lifecycleRegistersAndRemovesCapacityCommonAndCompatibilityMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        SyncQueueMetrics compatibility = new SyncQueueMetrics(registry);
        SyncQueueService service = service(compatibility, capacity);
        SyncQueueWorkloadMetricsSource source = new SyncQueueWorkloadMetricsSource(service, capacity);
        WorkloadMetricsBinder binder = new WorkloadMetricsBinder(registry, source);
        SyncQueueConfiguration configuration = new SyncQueueConfiguration();
        var listener = configuration.syncQueueLifecycleListener(service, binder);

        listener.onRegister(spec("fn", 2));
        assertNotNull(registry.find("function_queue_depth").tag("function", "fn").gauge());
        assertEquals(2, capacity.effectiveConcurrency("fn"));

        listener.onRemove("fn");
        assertNull(registry.find("function_queue_depth").tag("function", "fn").gauge());
        assertNull(registry.find("sync_queue_depth").tag("function", "fn").gauge());
        assertEquals(0, capacity.effectiveConcurrency("fn"));
    }

    @Test
    void removalRetainsAcquiredSlotUntilCompletionThenCleansIt() {
        FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        capacity.register("fn", 1);
        assertTrue(capacity.tryAcquireSlot("fn"));

        capacity.remove("fn");
        assertEquals(0, capacity.inFlight("fn"));
        capacity.releaseSlotAndGetHoldNanos("fn");
        assertEquals(0, capacity.effectiveConcurrency("fn"));
        assertFalse(capacity.tryAcquireSlot("fn"));
    }

    private static SyncQueueService service(SyncQueueMetrics metrics, FunctionCapacityRegistry capacity) {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2,
                Duration.ofSeconds(30), 3);
        return new SyncQueueService(props, new ExecutionStore(),
                new it.unimib.datai.nanofaas.modules.syncqueue.sync.WaitEstimator(Duration.ofSeconds(30), 3),
                metrics, java.time.Clock.systemUTC(), SyncQueueConfigSource.fixed(props.runtimeDefaults()),
                capacity, null);
    }

    private static FunctionSpec spec(String name, int concurrency) {
        return new FunctionSpec(name, "image", null, Map.of(), null, 1000, concurrency, 1, 3,
                null, ExecutionMode.LOCAL, null, null, null);
    }
}
