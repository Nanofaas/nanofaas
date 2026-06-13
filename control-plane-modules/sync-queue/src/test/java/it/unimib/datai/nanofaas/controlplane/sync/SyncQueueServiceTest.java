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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncQueueServiceTest {

    private static SyncQueueService createService(SyncQueueProperties props, ExecutionStore store,
                                                   WaitEstimator estimator, SyncQueueMetrics metrics, Clock clock) {
        SyncQueueConfigSource configSource = SyncQueueConfigSource.fixed(props.runtimeDefaults());
        return new SyncQueueService(props, store, estimator, metrics, clock, configSource);
    }

    @Test
    void rejectsWhenQueueIsFull() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 1, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(30), 3);
        SyncQueueMetrics metrics = new SyncQueueMetrics(new SimpleMeterRegistry());
        SyncQueueService service = createService(props, store, estimator, metrics, Clock.systemUTC());

        FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
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
        SyncQueueService service = createService(props, store, estimator, metrics, fixed);

        FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        InvocationTask task = new InvocationTask("e1", "fn", spec, new InvocationRequest("one", Map.of()), null, null, t0, 1);
        ExecutionRecord record = new ExecutionRecord("e1", task);
        store.put(record);

        service.enqueueOrThrow(task);

        service.peekReady(t0.plusSeconds(3));

        assertTrue(record.completion().isDone());
        assertEquals("QUEUE_TIMEOUT", record.completion().join().error().code());
    }

    @Test
    void awaitWork_unblocksWhenTaskIsEnqueued() throws Exception {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(30), 3);
        SyncQueueMetrics metrics = new SyncQueueMetrics(new SimpleMeterRegistry());
        SyncQueueService service = createService(props, store, estimator, metrics, Clock.systemUTC());

        CountDownLatch done = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            service.awaitWork(500);
            done.countDown();
        });
        waiter.start();

        FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        InvocationTask task = new InvocationTask("e1", "fn", spec, new InvocationRequest("one", Map.of()), null, null, Instant.now(), 1);
        store.put(new ExecutionRecord("e1", task));
        Thread.sleep(50);
        service.enqueueOrThrow(task);

        assertTrue(done.await(300, TimeUnit.MILLISECONDS));
        waiter.join(500);
    }

    @Test
    void pollReadyMatchingDoesNotSelectMatchBeyondScanLimitOnFirstCall() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 100, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(30), 3);
        SyncQueueMetrics metrics = new SyncQueueMetrics(new SimpleMeterRegistry());
        SyncQueueService service = createService(props, store, estimator, metrics, Clock.systemUTC());

        FunctionSpec blockedSpec = new FunctionSpec("blocked", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        for (int i = 0; i < SyncQueueService.POLL_READY_MATCHING_SCAN_LIMIT; i++) {
            InvocationTask task = new InvocationTask("blocked-" + i, "blocked", blockedSpec, new InvocationRequest("blocked", Map.of()), null, null, Instant.now(), 1);
            store.put(new ExecutionRecord(task.executionId(), task));
            service.enqueueOrThrow(task);
        }

        FunctionSpec readySpec = new FunctionSpec("ready", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        InvocationTask ready = new InvocationTask("ready", "ready", readySpec, new InvocationRequest("ready", Map.of()), null, null, Instant.now(), 1);
        store.put(new ExecutionRecord(ready.executionId(), ready));
        service.enqueueOrThrow(ready);

        SyncQueueItem selected = service.pollReadyMatching(Instant.now(), task -> task.functionName().equals("ready"));

        assertEquals(null, selected);
        assertEquals(SyncQueueService.POLL_READY_MATCHING_SCAN_LIMIT + 1, service.queuedItems());
    }

    @Test
    void findReadyMatchingDoesNotDequeueOrRecordWaitMetrics() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(30), 3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SyncQueueMetrics metrics = new SyncQueueMetrics(registry);
        SyncQueueService service = createService(props, store, estimator, metrics, Clock.systemUTC());

        FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        InvocationTask task = new InvocationTask("e1", "fn", spec, new InvocationRequest("one", Map.of()), null, null, Instant.now(), 1);
        store.put(new ExecutionRecord("e1", task));
        service.enqueueOrThrow(task);

        SyncQueueItem selected = service.findReadyMatching(Instant.now(), candidate -> candidate.functionName().equals("fn"));

        assertEquals(task, selected.task());
        assertEquals(1, service.queuedItems());
        assertEquals(1.0, registry.get("sync_queue_depth").gauge().value());
        assertEquals(0, registry.get("sync_queue_wait_seconds").timer().count());
    }
}
