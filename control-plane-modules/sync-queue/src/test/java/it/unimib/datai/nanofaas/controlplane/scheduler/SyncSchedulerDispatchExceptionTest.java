package it.unimib.datai.nanofaas.controlplane.scheduler;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.modules.runtimeconfig.RuntimeConfigService;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.controlplane.service.RateLimiter;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueMetrics;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

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
        when(enqueuer.tryAcquireSlot("fn")).thenReturn(true);

        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        SyncQueueMetrics metrics = new SyncQueueMetrics(registry);
        RuntimeConfigService configService = new RuntimeConfigService(new RateLimiter(), props.runtimeDefaults());
        SyncQueueService queue = new SyncQueueService(props, store, metrics, configService);

        InvocationTask task = new InvocationTask(
                "e1", "fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        );
        store.put(new ExecutionRecord("e1", task));
        queue.enqueueOrThrow(task);

        // Dispatch that throws an exception
        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, t -> {
            throw new RuntimeException("dispatch failed");
        });

        scheduler.tickOnce();

        verify(enqueuer).releaseSlot("fn");
    }

    @Test
    void dispatchSuccess_doesNotReleaseSlot() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InvocationEnqueuer enqueuer = mock(InvocationEnqueuer.class);
        FunctionSpec spec = new FunctionSpec(
                "fn", "image", null, Map.of(), null,
                1000, 1, 10, 3, null, ExecutionMode.LOCAL, null, null, null
        );
        when(enqueuer.tryAcquireSlot("fn")).thenReturn(true);

        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        SyncQueueMetrics metrics = new SyncQueueMetrics(registry);
        RuntimeConfigService configService = new RuntimeConfigService(new RateLimiter(), props.runtimeDefaults());
        SyncQueueService queue = new SyncQueueService(props, store, metrics, configService);

        InvocationTask task = new InvocationTask(
                "e1", "fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        );
        store.put(new ExecutionRecord("e1", task));
        queue.enqueueOrThrow(task);

        // Dispatch that succeeds (no exception)
        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, t -> { /* success */ });

        scheduler.tickOnce();

        verify(enqueuer, never()).releaseSlot("fn");
    }
}
