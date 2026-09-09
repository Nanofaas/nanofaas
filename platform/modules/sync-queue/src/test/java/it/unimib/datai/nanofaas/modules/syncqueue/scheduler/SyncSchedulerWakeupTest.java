package it.unimib.datai.nanofaas.modules.syncqueue.scheduler;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueInvocationEnqueuer;
import it.unimib.datai.nanofaas.modules.syncqueue.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueMetrics;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueService;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * P2 acceptance cells driven with the real {@link SyncScheduler} on its own thread against the
 * real {@link SyncQueueService} and a real capacity registry, wired exactly as the Spring
 * configuration wires them (the enqueuer notifies the queue service on every slot release). All
 * waits use latches and bounded futures - no fragile sleeps assert behaviour.
 */
class SyncSchedulerWakeupTest {

    private ExecutionStore store;
    private FunctionCapacityRegistry capacity;
    private SyncQueueService queue;
    private SyncQueueInvocationEnqueuer enqueuer;
    private InvocationService invocationService;
    private AtomicLong dispatched;
    private CountDownLatch dispatchLatch;
    private SyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        snapshotForeignSchedulerThreads();
        store = new ExecutionStore();
        capacity = new FunctionCapacityRegistry();
        queue = newQueue(store, capacity);
        enqueuer = new SyncQueueInvocationEnqueuer(capacity, null, queue::onDispatchSlotReleased, queue);
        invocationService = mock(InvocationService.class);
        dispatched = new AtomicLong();
        dispatchLatch = new CountDownLatch(0);
        doAnswer(invocation -> {
            dispatched.incrementAndGet();
            dispatchLatch.countDown();
            return null;
        }).when(invocationService).dispatch(any(InvocationTask.class));
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.stop();
            scheduler = null;
        }
    }

    @Test
    void slotRelease_wakesTheCapacityBlockedWorkerWithoutTheOldBackoffWait() throws Exception {
        // fn has a single slot; task1 holds it in flight, task2 waits queued. The worker parks on
        // capacity. Releasing task1's slot must wake it so task2 dispatches promptly.
        queue.registerFunction("fn", 1);
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(1);
        AtomicReference<String> order = new AtomicReference<>();
        doAnswer(invocation -> {
            InvocationTask t = invocation.getArgument(0);
            order.accumulateAndGet(t.executionId(), (a, b) -> a == null ? b : a + "," + b);
            if (t.executionId().equals("e1")) first.countDown();
            if (t.executionId().equals("e2")) second.countDown();
            return null;
        }).when(invocationService).dispatch(any(InvocationTask.class));

        startScheduler();
        queue.enqueueOrThrow(task("e1", "fn"));
        assertThat(first.await(2, TimeUnit.SECONDS)).isTrue();

        queue.enqueueOrThrow(task("e2", "fn"));
        // Let the worker actually park on the exhausted capacity.
        awaitParkedOrDispatch(second, Duration.ofMillis(300));

        // The release is the completion of the in-flight execution; under the old sleep backoff
        // the worker would not learn about it until its backoff expired (up to 50 ms of avoidable
        // latency). The release notification must wake it: this is observed as e2 dispatching.
        var captured = org.mockito.ArgumentCaptor.forClass(InvocationTask.class);
        org.mockito.Mockito.verify(invocationService).dispatch(captured.capture());
        captured.getValue().dispatchLease().release();

        assertThat(second.await(2, TimeUnit.SECONDS))
                .as("releasing a slot must wake the capacity-blocked worker so the queued "
                        + "execution dispatches without waiting out a backoff")
                .isTrue();
        assertThat(order.get()).isEqualTo("e1,e2");
    }

    @Test
    void registration_wakesTheWorkerBlockedOnMissingCapacity() throws Exception {
        // A task admitted while its function has no registered capacity parks the worker. The
        // registration (the A4 lifecycle drain scenario) must wake it.
        CountDownLatch dispatchedTask = new CountDownLatch(1);
        doAnswer(invocation -> {
            dispatchedTask.countDown();
            return null;
        }).when(invocationService).dispatch(any(InvocationTask.class));

        startScheduler();
        queue.enqueueOrThrow(task("e1", "fn"));
        awaitParkedOrDispatch(dispatchedTask, Duration.ofMillis(200));

        queue.registerFunction("fn", 2);

        assertThat(dispatchedTask.await(2, TimeUnit.SECONDS))
                .as("registering a function must wake a scheduler parked on its missing capacity")
                .isTrue();
    }

    @Test
    void emptyQueue_parkedWorkerIsNotABusyLoop() throws Exception {
        CountingScheduler counting = new CountingScheduler(enqueuer, queue, invocationService);
        scheduler = counting;
        counting.start();

        // Let the worker run for ~300 ms with nothing to do. A busy loop would tick thousands of
        // times; a parked worker ticks a handful (the first immediate visit, then the 500 ms
        // safety-bound wakeups).
        awaitTrue(() -> counting.tickCount.get() >= 1);
        long before = counting.tickCount.get();
        Thread.sleep(300);
        long ticks = counting.tickCount.get() - before;

        assertThat(ticks).as("an idle scheduler must park, not poll").isLessThan(10);
    }

    @Test
    void zeroCapacity_parkedWorkerIsNotABusyLoop() throws Exception {
        queue.registerFunction("fn", 1);
        queue.enqueueOrThrow(task("in-flight", "fn")); // occupies the only slot? no - see below
        queue.enqueueOrThrow(task("queued", "fn"));
        // Take the only slot ourselves so the queued item can never dispatch: the worker scans,
        // finds fn at its limit, and must park rather than spin.
        assertThat(enqueuer.tryAcquireSlot("fn")).isTrue();

        CountingScheduler counting = new CountingScheduler(enqueuer, queue, invocationService);
        scheduler = counting;
        counting.start();

        awaitTrue(() -> counting.tickCount.get() >= 1);
        long before = counting.tickCount.get();
        Thread.sleep(300);
        long ticks = counting.tickCount.get() - before;

        assertThat(ticks).as("a zero-capacity queue must park the worker, not spin it").isLessThan(30);
    }

    @Test
    void stop_interruptsTheParkedWaitPromptly() throws Exception {
        startScheduler();
        // Wait until the worker is actually parked on the empty queue's monitor.
        Thread schedulerThread = awaitSchedulerThreadParked();

        long started = System.nanoTime();
        scheduler.stop();
        long stopNanos = System.nanoTime() - started;

        schedulerThread.join(TimeUnit.SECONDS.toMillis(2));
        assertThat(schedulerThread.isAlive())
                .as("stop() must interrupt the parked wait so the worker thread exits")
                .isFalse();
        assertThat(stopNanos)
                .as("stop() must not wait out the 500 ms safety bound")
                .isLessThan(TimeUnit.MILLISECONDS.toNanos(300));
    }

    private void startScheduler() {
        scheduler = new SyncScheduler(enqueuer, queue, invocationService);
        scheduler.start();
        assertThat(scheduler.isRunning()).isTrue();
    }

    /**
     * Waits until either the latch releases (work actually dispatched) or the scheduler thread
     * reaches TIMED_WAITING (parked). Used to give the worker time to park before asserting the
     * wake-up behaviour; never sleeps a fixed amount as the assertion itself.
     */
    private void awaitParkedOrDispatch(CountDownLatch workDone, Duration budget) throws InterruptedException {
        long deadline = System.nanoTime() + budget.toNanos();
        while (System.nanoTime() < deadline) {
            if (workDone.getCount() == 0) {
                return;
            }
            Thread schedulerThread = findSchedulerThread();
            if (schedulerThread != null && schedulerThread.getState() == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.sleep(5);
        }
    }

    private Thread awaitSchedulerThreadParked() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Thread schedulerThread = findSchedulerThread();
            if (schedulerThread != null && schedulerThread.getState() == Thread.State.TIMED_WAITING) {
                return schedulerThread;
            }
            Thread.sleep(5);
        }
        Thread thread = findSchedulerThread();
        assertThat(thread).as("scheduler thread should exist and park").isNotNull();
        return thread;
    }

    /**
     * The scheduler thread belonging to THIS test's scheduler.
     *
     * <p>Matching on the name prefix alone is not enough: the module's Spring tests leave cached
     * application contexts behind, and a {@code SyncScheduler} bean started in one of them keeps
     * its own worker parked in TIMED_WAITING for the life of the JVM. Picking that thread made
     * "the worker exits after stop()" fail against a thread this test never started — green in
     * isolation, red in the full suite. Only threads that did not exist before this test started
     * its scheduler are candidates.
     */
    private Thread findSchedulerThread() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("nanofaas-sync-scheduler") && !preExistingSchedulerThreads.contains(t)) {
                return t;
            }
        }
        return null;
    }

    /** Scheduler threads already alive (foreign, usually from a cached Spring context) before setUp. */
    private final Set<Thread> preExistingSchedulerThreads = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private void snapshotForeignSchedulerThreads() {
        preExistingSchedulerThreads.clear();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("nanofaas-sync-scheduler")) {
                preExistingSchedulerThreads.add(t);
            }
        }
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static SyncQueueService newQueue(ExecutionStore store, FunctionCapacityRegistry capacity) {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 100, Duration.ofSeconds(2), Duration.ofSeconds(30), 2, Duration.ofSeconds(30), 3);
        return new SyncQueueService(props, store, new SyncQueueMetrics(new SimpleMeterRegistry()),
                SyncQueueConfigSource.fixed(props.runtimeDefaults()), capacity, null);
    }

    private static InvocationTask task(String executionId, String functionName) {
        FunctionSpec spec = new FunctionSpec(functionName, "image", null, Map.of(), null,
                1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        return new InvocationTask(executionId, functionName, spec,
                new InvocationRequest("payload", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
    }

    /** Counts scheduler visits so a busy loop is observable as a high tick rate. */
    private static class CountingScheduler extends SyncScheduler {
        final AtomicLong tickCount = new AtomicLong();

        CountingScheduler(InvocationEnqueuer enqueuer, SyncQueueService queue, InvocationService invocationService) {
            super(enqueuer, queue, invocationService);
        }

        @Override
        void tickOnce() {
            tickCount.incrementAndGet();
            super.tickOnce();
        }
    }
}
