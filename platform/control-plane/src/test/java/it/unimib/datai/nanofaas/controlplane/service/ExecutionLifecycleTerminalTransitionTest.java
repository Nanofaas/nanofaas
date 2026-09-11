package it.unimib.datai.nanofaas.controlplane.service;

import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionLifecycle;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The terminal transition owned by {@link ExecutionLifecycle}, exercised end-to-end with the
 * real store, key store, factory and completion handler, plus a counting dispatcher. These
 * tests assert the acceptance property of P04 beyond the factory's {@code isNew} flag: a
 * backend is started at most once per protected key, whatever evicts the outcome (weight or
 * TTL), however the key publication is ordered, and however observers or duplicate events
 * misbehave.
 *
 * <p>Every concurrent case is time-bound by a latch/barrier and a bounded {@code Future.get},
 * never by a sleep used as proof of ordering.
 */
class ExecutionLifecycleTerminalTransitionTest {

    private final AtomicLong clock = new AtomicLong();
    private final Ticker ticker = clock::get;

    private ExecutionStore store;
    private IdempotencyStore keys;
    private Metrics metrics;
    private InvocationExecutionFactory factory;
    private ExecutionCompletionHandler completionHandler;
    private DispatcherRouter dispatcher;
    private AtomicInteger dispatches;
    private ConcurrentMap<String, CompletableFuture<DispatchResult>> inFlight;

    private void rebuild(ExecutionStoreProperties props) {
        this.metrics = new Metrics(new SimpleMeterRegistry());
        this.dispatcher = mock(DispatcherRouter.class);
        this.dispatches = new AtomicInteger();
        this.inFlight = new ConcurrentHashMap<>();
        when(dispatcher.dispatchLocal(any())).thenAnswer(inv -> {
            dispatches.incrementAndGet();
            InvocationTask task = inv.getArgument(0);
            CompletableFuture<DispatchResult> future = new CompletableFuture<>();
            inFlight.put(task.executionId(), future);
            return future;
        });
        this.store = new ExecutionStore(props, ticker);
        this.keys = new IdempotencyStore(props, ticker);
        // The factory attaches the ExecutionLifecycle to the store, as in production.
        this.factory = new InvocationExecutionFactory(store, keys, metrics);
        this.completionHandler = new ExecutionCompletionHandler(store, RetryScheduler.unavailable(), dispatcher, metrics);
    }

    private static FunctionSpec spec() {
        return new FunctionSpec("fn", "img", List.of(), Map.of(), null,
                10000, 1, 10, 0, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
    }

    private static InvocationRequest request() {
        return new InvocationRequest("payload", Map.of());
    }

    /** Creates/reuses the keyed execution and runs the real admission (dispatch) for a new one. */
    private InvocationExecutionFactory.ExecutionLookup admitAndDispatch(String key) {
        InvocationExecutionFactory.ExecutionLookup lookup =
                factory.createOrReuseExecution("fn", spec(), request(), key, "trace", InvocationKind.SYNC);
        InvocationEnqueueSupport.admitIfNew(lookup,
                () -> completionHandler.dispatch(lookup.executionRecord().task()));
        return lookup;
    }

    /** Forces the Caffeine caches to run maintenance so an expired entry is actually evicted. */
    private void drain() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline
                && (store.size() != 0 || store.inFlightCount() != 0 || keys.size() != 0)) {
            // size()/inFlightCount()/size() each run cleanUp(); repeat until the expired
            // entries have left. Never a sleep as proof of ordering: the loop only spins
            // while entries remain and returns immediately once they are gone.
        }
    }

    @Test
    void weightEvictionDuringCompletionLeavesATombstoneAndNeverReDispatches() throws Exception {
        // An outcome budget smaller than the payload: settle declines the outcome, so the
        // payload is gone while the key must still answer for it (I6 / I2).
        rebuild(new ExecutionStoreProperties(Duration.ofMinutes(5), Duration.ofMinutes(30),
                Duration.ofSeconds(30), 100, 100, 100));

        InvocationExecutionFactory.ExecutionLookup first = admitAndDispatch("race");
        String executionId = first.executionRecord().executionId();
        assertThat(dispatches.get()).as("the protected key starts one backend").isEqualTo(1);

        inFlight.get(executionId).complete(DispatchResult.warm(InvocationResult.success("x".repeat(1000))));

        assertThat(store.outcomeOf(executionId)).as("oversized outcome is not retained").isNull();
        assertThat(keys.acquireOrGet("fn", "race").terminal()).isTrue();

        // A replay gets the tombstone, not a second dispatch.
        InvocationExecutionFactory.ExecutionLookup replay = factory.createOrReuseExecution(
                "fn", spec(), request(), "race", "trace-2", InvocationKind.SYNC);
        assertThat(replay.isNew()).isFalse();
        assertThat(replay.gone()).isTrue();
        assertThat(dispatches.get()).as("no second backend for the same key").isEqualTo(1);
    }

    @Test
    void ttlEvictionDuringCompletionRespectsTheRetentionBoundary() throws Exception {
        rebuild(ExecutionStoreProperties.of(Duration.ofSeconds(30), Duration.ofMinutes(30), Duration.ofSeconds(30)));

        InvocationExecutionFactory.ExecutionLookup first = admitAndDispatch("ttl");
        String executionId = first.executionRecord().executionId();
        inFlight.get(executionId).complete(DispatchResult.warm(InvocationResult.success("done")));

        // Within retention: the archived outcome is served, never a second execution.
        InvocationExecutionFactory.ExecutionLookup replay = factory.createOrReuseExecution(
                "fn", spec(), request(), "ttl", "trace-2", InvocationKind.SYNC);
        assertThat(replay.isNew()).isFalse();
        assertThat(replay.settledExecutionId()).isEqualTo(executionId);
        assertThat(dispatches.get()).isEqualTo(1);

        // Past the TTL, the outcome AND the key expire together; only then is a fresh
        // execution allowed - and the new one is a different execution.
        clock.addAndGet(Duration.ofSeconds(31).toNanos());
        drain();
        InvocationExecutionFactory.ExecutionLookup fresh = factory.createOrReuseExecution(
                "fn", spec(), request(), "ttl", "trace-3", InvocationKind.SYNC);
        assertThat(fresh.isNew()).isTrue();
        assertThat(fresh.executionRecord().executionId()).isNotEqualTo(executionId);
    }

    @Test
    void delayedKeyPublishAfterCompletionStillProtectsTheKey() throws Exception {
        // The dispatch completes inline and the execution settles BEFORE the admission
        // publishes the key (the no-queue production ordering). The key must still end
        // terminal, with no intermediate reclaimable published binding.
        rebuild(new ExecutionStoreProperties(Duration.ofMinutes(5), Duration.ofMinutes(30),
                Duration.ofSeconds(30), 100, 100, 100));

        InvocationExecutionFactory.ExecutionLookup first = factory.createOrReuseExecution(
                "fn", spec(), request(), "late", "trace", InvocationKind.SYNC);
        String executionId = first.executionRecord().executionId();
        first.executionRecord().markSuccess("x".repeat(1000));
        store.settle(first.executionRecord()); // settle before the key is published
        first.publishAdmission();               // now the key is published against a settled record

        assertThat(keys.acquireOrGet("fn", "late").terminal())
                .as("publishing after completion must still produce the terminal tombstone")
                .isTrue();

        InvocationExecutionFactory.ExecutionLookup replay = factory.createOrReuseExecution(
                "fn", spec(), request(), "late", "trace-2", InvocationKind.SYNC);
        assertThat(replay.isNew()).isFalse();
        assertThat(replay.gone()).isTrue();
        assertThat(replay.settledExecutionId()).isEqualTo(executionId);
        assertThat(dispatches.get()).as("no dispatcher was ever started").isZero();
    }

    @Test
    void concurrentReplaysDuringTheTerminalTransitionNeverStartANewExecution() throws Exception {
        rebuild(new ExecutionStoreProperties(Duration.ofMinutes(5), Duration.ofMinutes(30),
                Duration.ofSeconds(30), 100, 100, 100));

        // Pause a terminal observer at the archive boundary: the key is already terminal and
        // the record already removed, but the observer has not finished. Replays arriving
        // here must not re-claim the key.
        CountDownLatch observerPaused = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        store.onTerminal(record -> {
            observerPaused.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release the observer");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted while pausing the observer", e);
            }
        });

        InvocationExecutionFactory.ExecutionLookup first = admitAndDispatch("race");
        String executionId = first.executionRecord().executionId();

        ExecutorService settling = Executors.newSingleThreadExecutor();
        try {
            Future<?> settle = settling.submit(() ->
                    inFlight.get(executionId).complete(DispatchResult.warm(InvocationResult.success("x".repeat(1000)))));
            if (!observerPaused.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("observer did not reach the archive boundary");
            }

            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<Boolean>> replays = new java.util.ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    replays.add(pool.submit(() -> {
                        InvocationExecutionFactory.ExecutionLookup replay = factory.createOrReuseExecution(
                                "fn", spec(), request(), "race", "trace-r", InvocationKind.SYNC);
                        return replay.isNew();
                    }));
                }
                for (Future<Boolean> replay : replays) {
                    assertThat(replay.get(5, TimeUnit.SECONDS))
                            .as("a replay during the terminal transition must not be a new execution")
                            .isFalse();
                }
            } finally {
                pool.shutdownNow();
            }

            release.countDown();
            settle.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            settling.shutdownNow();
        }
        assertThat(dispatches.get()).isEqualTo(1);
    }

    @Test
    void aThrowingTerminalListenerDoesNotInterruptTheTransitionOrCleanup() {
        rebuild(ExecutionStoreProperties.of(Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofSeconds(30)));
        AtomicBoolean secondObserverRan = new AtomicBoolean();
        store.onTerminal(record -> {
            throw new RuntimeException("metric boom");
        });
        store.onTerminal(record -> secondObserverRan.set(true));

        InvocationExecutionFactory.ExecutionLookup first = admitAndDispatch("boom");
        String executionId = first.executionRecord().executionId();
        inFlight.get(executionId).complete(DispatchResult.warm(InvocationResult.success("ok")));

        // The invariants hold even though an observer threw: the record left the living, the
        // outcome is archived, the key is terminal, and the later observers still ran.
        assertThat(store.getOrNull(executionId)).isNull();
        assertThat(store.outcomeOf(executionId)).isNotNull();
        assertThat(keys.acquireOrGet("fn", "boom").terminal()).isTrue();
        assertThat(secondObserverRan.get()).isTrue();
        assertThat(dispatches.get()).isEqualTo(1);
    }

    @Test
    void doubleCompletionIsIdempotent() throws Exception {
        rebuild(ExecutionStoreProperties.of(Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofSeconds(30)));

        InvocationExecutionFactory.ExecutionLookup first = admitAndDispatch("twice");
        String executionId = first.executionRecord().executionId();
        ExecutionRecord record = first.executionRecord();
        inFlight.get(executionId).complete(DispatchResult.warm(InvocationResult.success("first")));

        // A duplicate completion - a late callback, a second delivery, a retry - must not
        // overwrite the settled result or start a second backend.
        completionHandler.completeExecution(executionId, DispatchResult.warm(InvocationResult.success("second")));

        assertThat(record.state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(store.outcomeOf(executionId).output()).isEqualTo("first");
        assertThat(keys.acquireOrGet("fn", "twice").terminal()).isTrue();
        assertThat(dispatches.get()).isEqualTo(1);
        assertThat(record.completion().isDone()).isTrue();
        assertThat(record.completion().join().output()).isEqualTo("first");
    }

    @Test
    void completionAgainstAdministrativeExpirySettlesExactlyOnce() throws Exception {
        // A real ticker and a very short maxLifetime: the record expires on its own (as in
        // production) rather than through a steered clock, which is what keeps the expiry
        // observation deterministic against Caffeine's scheduler.
        ExecutionStoreProperties props = new ExecutionStoreProperties(
                Duration.ofMinutes(5), Duration.ofMillis(80), Duration.ofSeconds(30), 100, 100, 11_600);
        this.metrics = new Metrics(new SimpleMeterRegistry());
        this.dispatcher = mock(DispatcherRouter.class);
        this.dispatches = new AtomicInteger();
        this.inFlight = new ConcurrentHashMap<>();
        when(dispatcher.dispatchLocal(any())).thenAnswer(inv -> {
            dispatches.incrementAndGet();
            InvocationTask task = inv.getArgument(0);
            CompletableFuture<DispatchResult> future = new CompletableFuture<>();
            inFlight.put(task.executionId(), future);
            return future;
        });
        this.store = new ExecutionStore(props, new SimpleMeterRegistry());
        this.keys = new IdempotencyStore(props, Ticker.systemTicker());
        this.factory = new InvocationExecutionFactory(store, keys, metrics);
        this.completionHandler = new ExecutionCompletionHandler(store, RetryScheduler.unavailable(), dispatcher, metrics);

        InvocationExecutionFactory.ExecutionLookup first = admitAndDispatch("expire");
        String executionId = first.executionRecord().executionId();
        ExecutionRecord record = first.executionRecord();

        // The administrative expiry concludes the record (the published key co-expires with
        // it, as designed - key and execution die together when the dispatch never returns).
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(record.isTerminal()).isTrue();
            assertThat(record.completion().isDone()).isTrue();
            assertThat(store.getOrNull(executionId)).isNull();
        });

        // The real dispatch result arrives late; it must not create a second outcome or
        // rewrite the expiry conclusion, and no second backend is started.
        inFlight.get(executionId).complete(DispatchResult.warm(InvocationResult.success("too-late")));

        assertThat(store.outcomeOf(executionId)).isNotNull();
        assertThat(store.getOrNull(executionId)).isNull();
        assertThat(dispatches.get()).isEqualTo(1);
    }
}
