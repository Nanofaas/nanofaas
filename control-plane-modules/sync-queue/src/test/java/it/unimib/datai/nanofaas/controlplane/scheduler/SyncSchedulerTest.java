package it.unimib.datai.nanofaas.controlplane.scheduler;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueMetrics;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SyncSchedulerTest {
    @Test
    void dispatchesWhenSlotAvailable() {
        InvocationEnqueuer enqueuer = mock(InvocationEnqueuer.class);
        FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        when(enqueuer.tryAcquireSlot("fn")).thenReturn(true);

        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        SyncQueueMetrics metrics = new SyncQueueMetrics(new SimpleMeterRegistry());
        SyncQueueConfigSource configSource = SyncQueueConfigSource.fixed(props.runtimeDefaults());
        SyncQueueService queue = new SyncQueueService(props, store, metrics, configSource);

        InvocationTask task = new InvocationTask("e1", "fn", spec, new InvocationRequest("one", Map.of()), null, null, Instant.now(), 1);
        store.put(new ExecutionRecord("e1", task));
        queue.enqueueOrThrow(task);

        AtomicInteger dispatchCount = new AtomicInteger();
        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, (t) -> dispatchCount.incrementAndGet());

        scheduler.tickOnce();

        assertEquals(1, dispatchCount.get());
    }
}
