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
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.execution.admission.WaitEstimator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Field;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueInvocationEnqueuer;

class SyncQueueServiceTest {

    @Test
    void removedFunctionMarkersDrainWithTheirLastGeneration() throws Exception {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3);
        SyncQueueService service = createService(props, new ExecutionStore(),
                new WaitEstimator(Duration.ofSeconds(30), 3),
                new SyncQueueMetrics(new SimpleMeterRegistry()), Clock.systemUTC());
        for (int i = 0; i < 1000; i++) {
            String name = "removed-" + i;
            service.registerFunction(name, 1);
            service.removeFunctionState(name);
        }

        Field field = SyncQueueService.class.getDeclaredField("removalFences");
        field.setAccessible(true);
        assertTrue(((Map<?, ?>) field.get(service)).isEmpty());
    }

    @Test
    void removingUnknownFunctionsDoesNotRetainNameTombstones() throws Exception {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3);
        SyncQueueService service = createService(props, new ExecutionStore(),
                new WaitEstimator(Duration.ofSeconds(30), 3),
                new SyncQueueMetrics(new SimpleMeterRegistry()), Clock.systemUTC());
        for (int i = 0; i < 1000; i++) {
            service.removeFunctionState("unknown-" + i);
        }

        Field field = SyncQueueService.class.getDeclaredField("removalFences");
        field.setAccessible(true);
        assertTrue(((Map<?, ?>) field.get(service)).isEmpty());
    }

    @Test
    void lifecycleLocksAreRemovedAfterRepeatedFunctionRemoval() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        SyncQueueService service = createService(props, new ExecutionStore(),
                new WaitEstimator(Duration.ofSeconds(30), 3),
                new SyncQueueMetrics(new SimpleMeterRegistry()), Clock.systemUTC());
        for (int i = 0; i < 20; i++) {
            service.registerFunction("cleanup", 1);
            service.removeFunctionState("cleanup");
        }
        assertEquals(0, service.lifecycleLockCount());
    }

    @Test
    void lateReleaseCleansUpRetiredFunctionLifecycleLock() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        SyncQueueService service = new SyncQueueService(
                props, new ExecutionStore(), new WaitEstimator(Duration.ofSeconds(30), 3),
                new SyncQueueMetrics(new SimpleMeterRegistry()), Clock.systemUTC(),
                SyncQueueConfigSource.fixed(props.runtimeDefaults()), registry, null);
        SyncQueueInvocationEnqueuer enqueuer = new SyncQueueInvocationEnqueuer(
                registry, null, service::onDispatchSlotReleased);
        service.registerFunction("fn", 1);

        var lease = enqueuer.tryAcquireLease(task("fn", "held"));
        assertNotNull(lease);
        service.removeFunctionState("fn");
        assertEquals(1, service.lifecycleLockCount());

        lease.release();

        assertEquals(0, service.lifecycleLockCount());
    }

    @Test
    void concurrentRemoveAndRegisterLeavesNewGenerationUsableAndOldSlotSafe() throws Exception {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        CountDownLatch removalBlocked = new CountDownLatch(1);
        CountDownLatch allowRemoval = new CountDownLatch(1);
        ExecutionStore store = new ExecutionStore() {
            @Override
            public ExecutionRecord getOrNull(String executionId) {
                removalBlocked.countDown();
                try {
                    allowRemoval.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }
        };
        SyncQueueService service = new SyncQueueService(
                props, store, new WaitEstimator(Duration.ofSeconds(30), 3),
                new SyncQueueMetrics(new SimpleMeterRegistry()), Clock.systemUTC(),
                SyncQueueConfigSource.fixed(props.runtimeDefaults()), capacity, null);
        capacity.register("fn", 1);
        var lease = capacity.tryAcquireLease("fn", 1);
        assertNotNull(lease);

        service.enqueueOrThrow(task("fn", "queued"));
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var remove = executor.submit(() -> service.removeFunctionState("fn"));
            assertTrue(removalBlocked.await(1, TimeUnit.SECONDS));
            var register = executor.submit(() -> service.registerFunction("fn", 1));
            lease.release();
            allowRemoval.countDown();
            assertNull(remove.get());
            assertNull(register.get());
        }

        assertNotNull(capacity.tryAcquireLease("fn", 1));
    }

    @Test
    void reRegistrationWhileDrainingReactivatesFunction() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        SyncQueueService service = new SyncQueueService(
                props, new ExecutionStore(), new WaitEstimator(Duration.ofSeconds(30), 3),
                new SyncQueueMetrics(meters), Clock.systemUTC(),
                SyncQueueConfigSource.fixed(props.runtimeDefaults()), capacity, null);

        service.registerFunction("fn", 1);
        var lease = capacity.tryAcquireLease("fn", 1);
        assertNotNull(lease);
        service.removeFunctionState("fn");

        // A redeploy while the previous invocation is still in flight is an ordinary
        // delete-and-recreate; it used to reach the HTTP layer as a 500.
        try {
            service.registerFunction("fn", 4);

            assertNotNull(capacity.state("fn"));
            assertEquals(4, capacity.configuredConcurrency("fn"));
            assertEquals(4, capacity.effectiveConcurrency("fn"));
            assertDoesNotThrow(() -> service.enqueueOrThrow(task("fn", "replacement")));
        } finally {
            lease.release();
        }
    }

    private static InvocationTask task(String functionName, String executionId) {
        FunctionSpec spec = new FunctionSpec(functionName, "image", null, Map.of(), null,
                1000, 1, 10, 3, null, ExecutionMode.LOCAL, null, null, null);
        return new InvocationTask(executionId, functionName, spec,
                new InvocationRequest("one", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
    }

    private static SyncQueueService createService(SyncQueueProperties props, ExecutionStore store,
                                                   WaitEstimator estimator, SyncQueueMetrics metrics, Clock clock) {
        SyncQueueConfigSource configSource = SyncQueueConfigSource.fixed(props.runtimeDefaults());
        return new SyncQueueService(props, store, estimator, metrics, clock, configSource, new FunctionCapacityRegistry(), null);
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
    void liveEstimatorOverflowUsesProductionRejectionWithFiniteRetryAfter() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, true, 10, Duration.ofSeconds(2), Duration.ofSeconds(30), 7,
                Duration.ofSeconds(10), 1);
        Instant now = Instant.parse("2026-09-10T10:00:00Z");
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 1, 3, 3, 1);
        estimator.recordDispatch("live-a", now);
        estimator.recordDispatch("live-b", now);
        estimator.recordDispatch("live-c", now);
        SyncQueueService service = createService(
                props, new ExecutionStore(), estimator,
                new SyncQueueMetrics(new SimpleMeterRegistry()), Clock.fixed(now, ZoneOffset.UTC));
        service.enqueueOrThrow(task("queued", "e-overflow-queued"));

        SyncQueueRejectedException rejection = assertThrows(
                SyncQueueRejectedException.class,
                () -> service.enqueueOrThrow(task("overflow", "e-overflow-rejected")));

        assertEquals(SyncQueueRejectReason.EST_WAIT, rejection.reason());
        assertEquals(7, rejection.retryAfterSeconds());
        assertTrue(rejection.retryAfterSeconds() > 0);
    }

    @Test
    void sampleSaturationWithUnusedFunctionSlotsRemainsAdmissible() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, true, 10, Duration.ofSeconds(10), Duration.ofSeconds(30), 7,
                Duration.ofSeconds(10), 1);
        Instant now = Instant.parse("2026-09-10T10:00:00Z");
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 1, 3, 3, 1);
        for (int i = 0; i < 10; i++) {
            estimator.recordDispatch("hot", now.plusNanos(i));
        }
        SyncQueueService service = createService(
                props, new ExecutionStore(), estimator,
                new SyncQueueMetrics(new SimpleMeterRegistry()), Clock.fixed(now, ZoneOffset.UTC));
        service.enqueueOrThrow(task("queued", "e-sample-cap-queued"));

        assertDoesNotThrow(() -> service.enqueueOrThrow(
                task("unused", "e-sample-cap-admitted")));
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
    void aQueueWaitTimeoutStillRecordsTheInvocationsEndToEndConclusion() {
        // The queue terminates this invocation itself — the completion handler never sees it.
        // It was still admitted, and it is exactly the population that appears under overload,
        // so its total must reach the e2e timer the concurrency governor steers on.
        Instant t0 = Instant.parse("2026-02-01T00:00:00Z");
        Clock fixed = Clock.fixed(t0, ZoneOffset.UTC);
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        it.unimib.datai.nanofaas.controlplane.service.Metrics coreMetrics =
                new it.unimib.datai.nanofaas.controlplane.service.Metrics(registry);
        // Constructing the handler is what registers the terminal listener on this store.
        // No dispatcher: this invocation never dispatches, it dies waiting in the queue.
        new it.unimib.datai.nanofaas.controlplane.service.ExecutionCompletionHandler(
                store, null, null, coreMetrics);
        SyncQueueService service = createService(props, store,
                new WaitEstimator(Duration.ofSeconds(30), 3),
                new SyncQueueMetrics(registry), fixed);

        FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        InvocationTask task = new InvocationTask("e1", "fn", spec, new InvocationRequest("one", Map.of()), null, null, t0, 1, InvocationKind.SYNC);
        store.put(new ExecutionRecord("e1", task));

        service.enqueueOrThrow(task);
        service.peekReady(t0.plusSeconds(3));

        assertEquals(1, coreMetrics.e2eLatency("fn").count());
    }

    @Test
    void perFunctionDepthTracksEveryMutationSite() {
        // T2: queuedItems(fn) must stop being an O(depth) scan under the queue monitor, and
        // the counter that replaces it is only worth anything if it survives every path that
        // can move an item: admission, dispatch, rotation, queue-wait timeout, and the drain
        // that a function removal performs.
        Instant t0 = Instant.parse("2026-02-01T00:00:00Z");
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        SyncQueueService service = createService(props, store,
                new WaitEstimator(Duration.ofSeconds(30), 3),
                new SyncQueueMetrics(new SimpleMeterRegistry()), Clock.fixed(t0, ZoneOffset.UTC));
        service.registerFunction("a", 4);
        service.registerFunction("b", 4);

        service.enqueueOrThrow(task("a", "a1"));
        service.enqueueOrThrow(task("a", "a2"));
        service.enqueueOrThrow(task("b", "b1"));
        assertEquals(2, service.queuedItems("a"));
        assertEquals(1, service.queuedItems("b"));

        // Dispatch removes one.
        SyncQueueItem polled = service.pollReady(t0);
        assertNotNull(polled);
        assertEquals(1, service.queuedItems("a"));

        // Rotation moves the head to the tail: the depth must not change.
        assertTrue(service.rotateReadyScanWindow(t0));
        assertEquals(1, service.queuedItems("a"));
        assertEquals(1, service.queuedItems("b"));

        // Removing a function drains its items.
        service.removeFunctionState("b");
        assertEquals(0, service.queuedItems("b"));
        assertEquals(1, service.queuedItems("a"));

        // A queue-wait timeout drops the rest.
        service.peekReady(t0.plusSeconds(30));
        assertEquals(0, service.queuedItems("a"));
        assertEquals(0, service.queuedItems());
    }

    @Test
    void awaitWakeup_returnsImmediatelyWhenTheEpochMovedBeforeTheWait() {
        // The whole point of the epoch discipline: a waiter reads the epoch BEFORE scanning, and
        // if a wake-up event fires in the gap between that scan and the park, the park must not
        // happen at all. Everything else in this file proves the epoch advances; this proves the
        // consuming half — without it a lost signal costs a full safety-bound wait.
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        SyncQueueService service = createService(props, new ExecutionStore(),
                new WaitEstimator(Duration.ofSeconds(30), 3),
                new SyncQueueMetrics(new SimpleMeterRegistry()), Clock.systemUTC());
        service.registerFunction("fn", 1);

        long observedEpoch = service.wakeupEpoch();
        service.enqueueOrThrow(task("fn", "e1")); // the signal the waiter is about to miss

        assertTimeoutPreemptively(Duration.ofMillis(500),
                () -> service.awaitWakeup(30_000, observedEpoch));
    }

    @Test
    void awaitWakeup_unblocksWhenTaskIsEnqueued() throws Exception {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(30), 3);
        SyncQueueMetrics metrics = new SyncQueueMetrics(new SimpleMeterRegistry());
        SyncQueueService service = createService(props, store, estimator, metrics, Clock.systemUTC());

        CountDownLatch done = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            long epoch = service.wakeupEpoch();
            service.awaitWakeup(500, epoch);
            done.countDown();
        });
        waiter.start();

        FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        InvocationTask task = new InvocationTask("e1", "fn", spec, new InvocationRequest("one", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
        store.put(new ExecutionRecord("e1", task));

        // Wait until the waiter is blocked inside awaitWakeup's workSignal.wait(500)
        // so the enqueue below is what wakes it, not an epoch that already moved.
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                assertEquals(Thread.State.TIMED_WAITING, waiter.getState()));

        service.enqueueOrThrow(task);

        assertTrue(done.await(300, TimeUnit.MILLISECONDS));
        waiter.join(500);
    }

    @Test
    void awaitWakeup_returnsWhenTimeoutExpiresWithoutSignal() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        SyncQueueService service = createService(props, new ExecutionStore(),
                new WaitEstimator(Duration.ofSeconds(30), 3), new SyncQueueMetrics(new SimpleMeterRegistry()), Clock.systemUTC());

        long epoch = service.wakeupEpoch();
        assertTimeoutPreemptively(Duration.ofMillis(250), () -> service.awaitWakeup(10, epoch));
    }

    @Test
    void capacityRelease_advancesWakeupEpochWhenQueueHasQueuedWork() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        SyncQueueService service = new SyncQueueService(
                props, new ExecutionStore(), new WaitEstimator(Duration.ofSeconds(30), 3),
                new SyncQueueMetrics(new SimpleMeterRegistry()), Clock.systemUTC(),
                SyncQueueConfigSource.fixed(props.runtimeDefaults()), registry, null);
        SyncQueueInvocationEnqueuer enqueuer = new SyncQueueInvocationEnqueuer(
                registry, null, service::onDispatchSlotReleased, service);
        service.registerFunction("fn", 1);
        var lease = enqueuer.tryAcquireLease(task("fn", "held"));
        assertNotNull(lease);
        service.enqueueOrThrow(task("queued", "fn"));

        long epochBefore = service.wakeupEpoch();
        lease.release();

        assertTrue(service.wakeupEpoch() > epochBefore,
                "a slot release with queued work must advance the wakeup sequence so a parked "
                        + "scheduler re-scans instead of sleeping through the freed slot");
    }

    @Test
    void capacityRelease_doesNotWakeWhenQueueIsEmpty() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        SyncQueueService service = new SyncQueueService(
                props, new ExecutionStore(), new WaitEstimator(Duration.ofSeconds(30), 3),
                new SyncQueueMetrics(new SimpleMeterRegistry()), Clock.systemUTC(),
                SyncQueueConfigSource.fixed(props.runtimeDefaults()), registry, null);
        SyncQueueInvocationEnqueuer enqueuer = new SyncQueueInvocationEnqueuer(
                registry, null, service::onDispatchSlotReleased, service);
        service.registerFunction("fn", 1);
        var lease = enqueuer.tryAcquireLease(task("fn", "held"));
        assertNotNull(lease);

        long epochBefore = service.wakeupEpoch();
        lease.release();

        assertEquals(epochBefore, service.wakeupEpoch(),
                "a release with no queued work must not wake an idle scheduler");
    }

    @Test
    void registration_advancesWakeupEpochWhenQueueHasQueuedWork() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        SyncQueueService service = new SyncQueueService(
                props, new ExecutionStore(), new WaitEstimator(Duration.ofSeconds(30), 3),
                new SyncQueueMetrics(new SimpleMeterRegistry()), Clock.systemUTC(),
                SyncQueueConfigSource.fixed(props.runtimeDefaults()), registry, null);
        // A task queued while the function has no registered capacity yet.
        service.enqueueOrThrow(task("queued", "fn"));

        long epochBefore = service.wakeupEpoch();
        service.registerFunction("fn", 1);

        assertTrue(service.wakeupEpoch() > epochBefore,
                "registering a function with queued work must wake a scheduler parked on its "
                        + "missing capacity");
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
    void staleEnqueuesStayRejectedUntilTheirRemovedGenerationDrains() throws Exception {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(30), 3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SyncQueueMetrics metrics = new SyncQueueMetrics(registry);
        FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        SyncQueueService service = new SyncQueueService(
                props, store, estimator, metrics, Clock.systemUTC(),
                SyncQueueConfigSource.fixed(props.runtimeDefaults()), capacity, null);

        FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        InvocationTask first = new InvocationTask("e1", "fn", spec, new InvocationRequest("one", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
        InvocationTask second = new InvocationTask("e2", "fn", spec, new InvocationRequest("two", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
        ExecutionRecord firstRecord = new ExecutionRecord("e1", first);
        ExecutionRecord secondRecord = new ExecutionRecord("e2", second);
        store.put(firstRecord);
        store.put(secondRecord);

        service.registerFunction("fn", 1);
        var lease = capacity.tryAcquireLease("fn", 1);
        assertNotNull(lease);
        service.removeFunctionState("fn");

        assertThrows(SyncQueueRejectedException.class, () -> service.enqueueOrThrow(first));
        assertThrows(SyncQueueRejectedException.class, () -> service.enqueueOrThrow(second));
        assertEquals(0, service.queuedItems());
        assertTrue(firstRecord.completion().isDone());
        assertTrue(secondRecord.completion().isDone());
        assertEquals("FUNCTION_REMOVED", firstRecord.completion().join().error().code());
        assertEquals("FUNCTION_REMOVED", secondRecord.completion().join().error().code());
        assertEquals(null, registry.find("sync_queue_depth").tag("function", "fn").gauge());
        assertEquals(null, registry.find("sync_queue_admitted_total").tag("function", "fn").counter());

        Field field = SyncQueueService.class.getDeclaredField("removalFences");
        field.setAccessible(true);
        assertEquals(1, ((Map<?, ?>) field.get(service)).size());
        lease.release();
        service.onDispatchSlotReleased("fn");
        assertTrue(((Map<?, ?>) field.get(service)).isEmpty());
    }

    @Test
    void enqueueAfterUnregisteredRemovalCompletesAsFunctionRemovedWithoutRetainingTheName() throws Exception {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SyncQueueService service = createService(props, store,
                new WaitEstimator(Duration.ofSeconds(30), 3),
                new SyncQueueMetrics(registry), Clock.systemUTC());
        InvocationTask task = task("fn", "e1");
        ExecutionRecord record = new ExecutionRecord("e1", task);
        store.put(record);

        service.removeFunctionState("fn");

        assertThrows(SyncQueueRejectedException.class, () -> service.enqueueOrThrow(task));
        assertTrue(record.completion().isDone());
        assertEquals("FUNCTION_REMOVED", record.completion().join().error().code());
        Field field = SyncQueueService.class.getDeclaredField("removalFences");
        field.setAccessible(true);
        assertTrue(((Map<?, ?>) field.get(service)).isEmpty());
        assertEquals(null, registry.find("sync_queue_depth").tag("function", "fn").gauge());
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
        service.registerFunction("fn", 1);

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
