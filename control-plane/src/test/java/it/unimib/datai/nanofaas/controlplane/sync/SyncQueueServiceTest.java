package it.unimib.datai.nanofaas.controlplane.sync;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncQueueServiceTest {
    @Test
    void rejectsWhenQueueIsFull() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 1, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(30), 3);
        SyncQueueMetrics metrics = new SyncQueueMetrics(new SimpleMeterRegistry());
        SyncQueueService service = new SyncQueueService(props, store, estimator, metrics, Clock.systemUTC());

        FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null);
        InvocationTask task1 = new InvocationTask("e1", "fn", spec, new InvocationRequest("one", Map.of()), null, null, Instant.now(), 1);
        InvocationTask task2 = new InvocationTask("e2", "fn", spec, new InvocationRequest("two", Map.of()), null, null, Instant.now(), 1);
        store.put(new ExecutionRecord("e1", task1));
        store.put(new ExecutionRecord("e2", task2));

        service.enqueueOrThrow(task1);

        SyncQueueRejectedException ex = assertThrows(SyncQueueRejectedException.class, () -> service.enqueueOrThrow(task2));
        assertEquals(SyncQueueRejectReason.DEPTH, ex.reason());
    }

    @Test
    void timesOutQueuedItem() {
        Instant t0 = Instant.parse("2026-02-01T00:00:00Z");
        Clock fixed = Clock.fixed(t0, ZoneOffset.UTC);
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(30), 3);
        SyncQueueMetrics metrics = new SyncQueueMetrics(new SimpleMeterRegistry());
        SyncQueueService service = new SyncQueueService(props, store, estimator, metrics, fixed);

        FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null);
        InvocationTask task = new InvocationTask("e1", "fn", spec, new InvocationRequest("one", Map.of()), null, null, t0, 1);
        ExecutionRecord record = new ExecutionRecord("e1", task);
        store.put(record);

        service.enqueueOrThrow(task);

        service.peekReady(t0.plusSeconds(3));

        assertTrue(record.completion().isDone());
        assertEquals("QUEUE_TIMEOUT", record.completion().join().error().code());
    }
}
