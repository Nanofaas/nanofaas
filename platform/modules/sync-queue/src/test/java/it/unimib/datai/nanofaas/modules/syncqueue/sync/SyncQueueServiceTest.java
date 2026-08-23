package it.unimib.datai.nanofaas.modules.syncqueue.sync;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.modules.syncqueue.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
        InvocationTask task1 = new InvocationTask("e1", "fn", spec, new InvocationRequest("one", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
        InvocationTask task2 = new InvocationTask("e2", "fn", spec, new InvocationRequest("two", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
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
        InvocationTask task = new InvocationTask("e1", "fn", spec, new InvocationRequest("one", Map.of()), null, null, t0, 1, InvocationKind.SYNC);
        ExecutionRecord executionRecord = new ExecutionRecord("e1", task);
        store.put(executionRecord);

        service.enqueueOrThrow(task);

        service.peekReady(t0.plusSeconds(3));

        assertTrue(executionRecord.completion().isDone());
        assertEquals("QUEUE_TIMEOUT", executionRecord.completion().join().error().code());
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
        InvocationTask task = new InvocationTask("e1", "fn", spec, new InvocationRequest("one", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
        store.put(new ExecutionRecord("e1", task));

        // Wait until the waiter is blocked inside awaitWork's workSignal.wait(500)
        // so the enqueue below is what wakes it, not a queue that is already non-empty
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                assertEquals(Thread.State.TIMED_WAITING, waiter.getState()));

        service.enqueueOrThrow(task);

        assertTrue(done.await(300, TimeUnit.MILLISECONDS));
        waiter.join(500);
    }

    @Test
    void awaitWork_returnsWhenTimeoutExpiresWithoutWork() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        SyncQueueService service = createService(props, new ExecutionStore(),
                new WaitEstimator(Duration.ofSeconds(30), 3), new SyncQueueMetrics(new SimpleMeterRegistry()), Clock.systemUTC());

        assertTimeoutPreemptively(Duration.ofMillis(250), () -> service.awaitWork(10));
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
            InvocationTask task = new InvocationTask("blocked-" + i, "blocked", blockedSpec, new InvocationRequest("blocked", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
            store.put(new ExecutionRecord(task.executionId(), task));
            service.enqueueOrThrow(task);
        }

        FunctionSpec readySpec = new FunctionSpec("ready", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        InvocationTask ready = new InvocationTask("ready", "ready", readySpec, new InvocationRequest("ready", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
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
        InvocationTask task = new InvocationTask("e1", "fn", spec, new InvocationRequest("one", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
        store.put(new ExecutionRecord("e1", task));
        service.enqueueOrThrow(task);

        SyncQueueItem selected = service.findReadyMatching(Instant.now(), candidate -> candidate.functionName().equals("fn"));

        assertEquals(task, selected.task());
        assertEquals(1, service.queuedItems());
        assertEquals(1.0, registry.get("sync_queue_depth").gauge().value());
        assertEquals(0, registry.get("sync_queue_wait_seconds").timer().count());
    }

    @Test
    void findReadyMatching_timesOutStaleItemThenSelectsReadyItem() {
        Instant t0 = Instant.parse("2026-02-01T00:00:00Z");
        MutableClock clock = new MutableClock(t0);
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofMillis(100), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(30), 3);
        SyncQueueMetrics metrics = new SyncQueueMetrics(new SimpleMeterRegistry());
        SyncQueueService service = createService(props, store, estimator, metrics, clock);

        FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        InvocationTask stale = new InvocationTask("stale", "fn", spec, new InvocationRequest("stale", Map.of()), null, null, t0, 1, InvocationKind.SYNC);
        ExecutionRecord staleRecord = new ExecutionRecord("stale", stale);
        store.put(staleRecord);
        service.enqueueOrThrow(stale);

        clock.advance(Duration.ofMillis(50));
        InvocationTask ready = new InvocationTask("ready", "ready", spec, new InvocationRequest("ready", Map.of()), null, null, clock.instant(), 1, InvocationKind.SYNC);
        store.put(new ExecutionRecord("ready", ready));
        service.enqueueOrThrow(ready);

        // t0+120ms: stale (enqueued t0, maxQueueWait 100ms) timed out; ready (enqueued t0+50ms) still fresh and selected
        SyncQueueItem selected = service.findReadyMatching(t0.plusMillis(120), candidate -> candidate.functionName().equals("ready"));

        assertEquals(ready, selected.task());
        assertEquals(1, service.queuedItems());
        assertTrue(staleRecord.completion().isDone());
        assertEquals("QUEUE_TIMEOUT", staleRecord.completion().join().error().code());
    }

    @Test
    void removeFunctionState_drainsQueuedItemsAndRemovesMeters() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(30), 3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SyncQueueMetrics metrics = new SyncQueueMetrics(registry);
        SyncQueueService service = createService(props, store, estimator, metrics, Clock.systemUTC());

        FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        InvocationTask task = new InvocationTask("e1", "fn", spec, new InvocationRequest("one", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
        ExecutionRecord executionRecord = new ExecutionRecord("e1", task);
        store.put(executionRecord);
        service.enqueueOrThrow(task);
        assertEquals(1, service.queuedItems());
        assertEquals(1.0, registry.get("sync_queue_depth").tag("function", "fn").gauge().value());

        service.removeFunctionState("fn");

        assertEquals(0, service.queuedItems());
        assertTrue(executionRecord.completion().isDone());
        assertEquals("FUNCTION_REMOVED", executionRecord.completion().join().error().code());
        assertEquals(null, registry.find("sync_queue_depth").tag("function", "fn").gauge());
        assertEquals(null, registry.find("sync_queue_admitted_total").tag("function", "fn").counter());
    }

    @Test
    void enqueueAfterRemoveFunctionStateCompletesAsFunctionRemovedWithoutRecreatingMeters() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(30), 3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SyncQueueMetrics metrics = new SyncQueueMetrics(registry);
        SyncQueueService service = createService(props, store, estimator, metrics, Clock.systemUTC());

        FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        InvocationTask task = new InvocationTask("e1", "fn", spec, new InvocationRequest("one", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
        ExecutionRecord executionRecord = new ExecutionRecord("e1", task);
        store.put(executionRecord);

        service.removeFunctionState("fn");

        assertThrows(SyncQueueRejectedException.class, () -> service.enqueueOrThrow(task));
        assertEquals(0, service.queuedItems());
        assertTrue(executionRecord.completion().isDone());
        assertEquals("FUNCTION_REMOVED", executionRecord.completion().join().error().code());
        assertEquals(null, registry.find("sync_queue_depth").tag("function", "fn").gauge());
        assertEquals(null, registry.find("sync_queue_admitted_total").tag("function", "fn").counter());
    }

    @Test
    void registerFunctionReenablesSyncQueueMetersBeforeRejectedAdmission() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 0, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(30), 3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SyncQueueMetrics metrics = new SyncQueueMetrics(registry);
        SyncQueueService service = createService(props, store, estimator, metrics, Clock.systemUTC());

        FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        InvocationTask task = new InvocationTask("e1", "fn", spec, new InvocationRequest("one", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
        store.put(new ExecutionRecord("e1", task));

        service.removeFunctionState("fn");
        service.registerFunction("fn");

        SyncQueueRejectedException ex = assertThrows(SyncQueueRejectedException.class, () -> service.enqueueOrThrow(task));

        assertEquals(SyncQueueRejectReason.DEPTH, ex.reason());
        assertEquals(1.0, registry.get("sync_queue_rejected_total").tag("function", "fn").counter().count());
    }

    /** Test-only clock with a mutable instant; avoid Thread.sleep-based timing in tests (S2925). */
    private static class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }
    }
}
