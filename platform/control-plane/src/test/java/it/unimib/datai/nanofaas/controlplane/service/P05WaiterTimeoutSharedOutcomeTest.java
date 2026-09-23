package it.unimib.datai.nanofaas.controlplane.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.dispatch.LocalDispatcher;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadContext;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadFailedException;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadGateway;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadTrigger;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P05: per-waiter timeouts and unconditional offload finalization (findings R3 and R4).
 *
 * <p>The single contract (ADR 0001 §5, invariant I1): a waiter's own budget concludes only
 * that waiter's wait. The shared record, key, store, lease and counters are untouched; the
 * shared execution keeps running and its real result is what the long waiter and the replay
 * observe. An execution-level deadline (the sync queue's wait expiry) is the distinct event
 * that concludes the whole execution with {@code TIMEOUT}.
 */
class P05WaiterTimeoutSharedOutcomeTest {

    private static FunctionSpec spec(String name) {
        return spec(name, 0);
    }

    private static FunctionSpec spec(String name, int maxRetries) {
        return new FunctionSpec(name, "img", List.of(), Map.of(), null,
                10000, 1, 10, maxRetries, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
    }

    private static InvocationExecutionFactory.ExecutionLookup lookup(
            InvocationExecutionFactory factory, String key) {
        return lookup(factory, key, spec("fn"));
    }

    private static InvocationExecutionFactory.ExecutionLookup lookup(
            InvocationExecutionFactory factory, String key, FunctionSpec spec) {
        return factory.createOrReuseExecution("fn", spec,
                new InvocationRequest("payload", Map.of()), key, null, InvocationKind.SYNC);
    }

    /** A coordinator wired with a controllable backend dispatch future. */
    private static CoordinatorHarness harnessWithBackend(CompletableFuture<DispatchResult> backend) {
        return harnessWithBackend(backend, InvocationEnqueuer.noOp());
    }

    private static CoordinatorHarness harnessWithBackend(CompletableFuture<DispatchResult> backend,
                                                         InvocationEnqueuer enqueuer) {
        ExecutionStore store = new ExecutionStore();
        IdempotencyStore keys = new IdempotencyStore();
        Metrics metrics = new Metrics(new SimpleMeterRegistry());
        TestWaiterCapacity.Runtime runtime = TestWaiterCapacity.runtime(store, keys, metrics, "fn");
        InvocationExecutionFactory factory = runtime.factory();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(store, enqueuer::enqueue,
                new DispatcherRouter(new LocalDispatcher() {
                    @Override
                    public CompletableFuture<DispatchResult> dispatch(InvocationTask task) {
                        return backend;
                    }
                }, null), metrics);
        ReactiveInvocationCoordinator coordinator =
                new ReactiveInvocationCoordinator(enqueuer, metrics, null, null, handler,
                        new InvocationResponseMapper(), runtime.waiters());
        return new CoordinatorHarness(store, keys, metrics, factory, handler, coordinator);
    }

    private record CoordinatorHarness(ExecutionStore store,
                                      IdempotencyStore keys,
                                      Metrics metrics,
                                      InvocationExecutionFactory factory,
                                      ExecutionCompletionHandler handler,
                                      ReactiveInvocationCoordinator coordinator) {
    }

    @Test
    void aShortWaiterArrivingBeforeALongWaiter_leavesTheRealSuccessForBoth() throws Exception {
        CompletableFuture<DispatchResult> backend = new CompletableFuture<>();
        CoordinatorHarness h = harnessWithBackend(backend);

        // The short waiter arrives first, admits the execution, dispatches, then its own
        // budget expires: it gets the timeout response and nothing else changes.
        SyncInvocation shortWaiter = h.coordinator().invoke(lookup(h.factory(), "key"), spec("fn"), 1)
                .block(Duration.ofSeconds(5));
        assertThat(shortWaiter.response().status()).isEqualTo("timeout");

        // The long waiter arrives after, finds the same live execution and parks on it.
        CompletableFuture<SyncInvocation> longWaiter =
                h.coordinator().invoke(lookup(h.factory(), "key"), spec("fn"), 10_000).toFuture();

        backend.complete(DispatchResult.warm(InvocationResult.success("real answer")));

        SyncInvocation longResult = longWaiter.get(5, TimeUnit.SECONDS);
        assertThat(longResult.response().status()).isEqualTo("success");
        assertThat(longResult.response().output()).isEqualTo("real answer");

        SyncInvocation replay = h.coordinator().invoke(lookup(h.factory(), "key"), spec("fn"), 10_000)
                .block(Duration.ofSeconds(5));
        assertThat(replay.response().status())
                .as("the replay of the same key must observe the shared success, not the short waiter's timeout")
                .isEqualTo("success");
    }

    @Test
    void anErrorBackendAnswer_stillReachesTheLongWaiterAndTheReplay() throws Exception {
        CompletableFuture<DispatchResult> backend = new CompletableFuture<>();
        CoordinatorHarness h = harnessWithBackend(backend);

        CompletableFuture<SyncInvocation> longWaiter =
                h.coordinator().invoke(lookup(h.factory(), "key"), spec("fn"), 10_000).toFuture();

        SyncInvocation shortWaiter = h.coordinator().invoke(lookup(h.factory(), "key"), spec("fn"), 1)
                .block(Duration.ofSeconds(5));
        assertThat(shortWaiter.response().status()).isEqualTo("timeout");

        backend.complete(DispatchResult.warm(InvocationResult.error("BACKEND_ERROR", "boom")));

        SyncInvocation longResult = longWaiter.get(5, TimeUnit.SECONDS);
        assertThat(longResult.response().status()).isEqualTo("error");
        assertThat(longResult.response().error().code()).isEqualTo("BACKEND_ERROR");

        SyncInvocation replay = h.coordinator().invoke(lookup(h.factory(), "key"), spec("fn"), 10_000)
                .block(Duration.ofSeconds(5));
        assertThat(replay.response().status()).isEqualTo("error");
        assertThat(replay.response().error().code()).isEqualTo("BACKEND_ERROR");
    }

    @Test
    void whenEveryWaiterTimesOut_theSharedExecutionStillConcludesForTheReplay() throws Exception {
        CompletableFuture<DispatchResult> backend = new CompletableFuture<>();
        CoordinatorHarness h = harnessWithBackend(backend);

        // Two waiters, both with budgets that expire before the backend answers. Neither
        // of their timeouts touches the shared execution.
        SyncInvocation first = h.coordinator().invoke(lookup(h.factory(), "key"), spec("fn"), 1)
                .block(Duration.ofSeconds(5));
        SyncInvocation second = h.coordinator().invoke(lookup(h.factory(), "key"), spec("fn"), 1)
                .block(Duration.ofSeconds(5));
        assertThat(first.response().status()).isEqualTo("timeout");
        assertThat(second.response().status()).isEqualTo("timeout");

        // The shared execution keeps running and concludes with the real result.
        backend.complete(DispatchResult.warm(InvocationResult.success("still arrived")));

        SyncInvocation replay = h.coordinator().invoke(lookup(h.factory(), "key"), spec("fn"), 10_000)
                .block(Duration.ofSeconds(5));
        assertThat(replay.response().status()).isEqualTo("success");
        assertThat(replay.response().output()).isEqualTo("still arrived");
    }

    @Test
    void anOffloadedFailureAfterAShortWaiterTimeout_stillConcludesTheLocalAttempt() {
        ExecutionStore store = new ExecutionStore();
        IdempotencyStore keys = new IdempotencyStore();
        Metrics metrics = new Metrics(new SimpleMeterRegistry());
        Sinks.One<InvocationResult> remote = Sinks.one();
        OffloadGateway gateway = new OffloadGateway() {
            @Override
            public boolean enabled() { return true; }

            @Override
            public boolean shouldOffloadEagerly(FunctionSpec s) {
                return true;
            }

            @Override
            public boolean shouldOffloadOnPressure(FunctionSpec s) {
                return false;
            }

            @Override
            public String targetUrl(FunctionSpec s) {
                return "http://remote";
            }

            @Override
            public Mono<InvocationResult> invokeRemote(InvocationTask task, OffloadTrigger trigger,
                                                       OffloadContext context, int budget) {
                return remote.asMono();
            }
        };
        TestWaiterCapacity.Runtime runtime = TestWaiterCapacity.runtime(store, keys, metrics, "fn");
        InvocationExecutionFactory factory = runtime.factory();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(store, null,
                new DispatcherRouter(new LocalDispatcher(), null), metrics);
        ReactiveInvocationCoordinator coordinator =
                new ReactiveInvocationCoordinator(null, metrics, null, gateway, handler,
                        new InvocationResponseMapper(), runtime.waiters());

        InvocationExecutionFactory.ExecutionLookup first = lookup(factory, "offloaded");
        CompletableFuture<SyncInvocation> owner =
                coordinator.invoke(first, spec("fn"), 10_000).toFuture();

        // A shorter idempotent waiter times out while the owner's remote call is pending.
        coordinator.invoke(lookup(factory, "offloaded"), spec("fn"), 1)
                .block(Duration.ofSeconds(5));

        try {
            // The remote call now fails. The failure must still conclude the local attempt:
            // the shared future completes and the record is settled (archived).
            remote.tryEmitError(new OffloadFailedException("http://remote", false, "remote refused"));
            assertThat(store.inFlightCount())
                    .as("a failed offloaded execution must not remain in the live store")
                    .isZero();
            assertThat(first.executionRecord().completion())
                    .as("the shared completion future must be concluded by the remote failure")
                    .isCompletedExceptionally();
            assertThat(owner)
                    .as("the original caller must observe the remote failure")
                    .isDone();
        } finally {
            owner.cancel(true);
        }
    }

    @Test
    void aShortWaiterTimeoutAcrossARetry_leavesTheRealSuccessForTheLongWaiterAndReplay() throws Exception {
        ExecutionStore store = new ExecutionStore();
        IdempotencyStore keys = new IdempotencyStore();
        Metrics metrics = new Metrics(new SimpleMeterRegistry());
        AtomicInteger attempts = new AtomicInteger();
        CompletableFuture<DispatchResult> attempt1 = new CompletableFuture<>();
        CompletableFuture<DispatchResult> attempt2 = new CompletableFuture<>();
        DispatcherRouter router = new DispatcherRouter(new LocalDispatcher() {
            @Override
            public CompletableFuture<DispatchResult> dispatch(InvocationTask task) {
                return attempts.getAndIncrement() == 0 ? attempt1 : attempt2;
            }
        }, null);
        // A retry-capable enqueuer: enqueue succeeds, and this test drives the dispatch by hand.
        InvocationEnqueuer enqueuer = new InvocationEnqueuer() {
            @Override
            public boolean enqueue(InvocationTask task) {
                return true;
            }

            @Override
            public boolean supportsAsync() { return true; }
            @Override public QueueStrategy queueStrategy() { return QueueStrategy.FUNCTION_QUEUE; }
        };
        TestWaiterCapacity.Runtime runtime = TestWaiterCapacity.runtime(store, keys, metrics, "fn");
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(store, enqueuer::enqueue, router, metrics);
        ReactiveInvocationCoordinator coordinator =
                new ReactiveInvocationCoordinator(enqueuer, metrics, null, null, handler,
                        new InvocationResponseMapper(), runtime.waiters());
        InvocationExecutionFactory factory = runtime.factory();
        FunctionSpec spec = spec("fn", 1);

        InvocationExecutionFactory.ExecutionLookup first = lookup(factory, "key", spec);
        CompletableFuture<SyncInvocation> longWaiter = coordinator.invoke(first, spec, 10_000).toFuture();

        // The enqueuer accepted the task; drive the first dispatch.
        handler.dispatch(first.executionRecord().task());

        // A short waiter times out while the first attempt is in flight.
        SyncInvocation shortWaiter = coordinator.invoke(lookup(factory, "key", spec), spec, 1)
                .block(Duration.ofSeconds(5));
        assertThat(shortWaiter.response().status()).isEqualTo("timeout");

        // Attempt 1 fails and is retried (attempt 2), which this test dispatches by hand.
        attempt1.complete(DispatchResult.warm(InvocationResult.error("TRANSIENT", "first try")));
        handler.dispatch(first.executionRecord().task());

        attempt2.complete(DispatchResult.warm(InvocationResult.success("real answer")));

        SyncInvocation longResult = longWaiter.get(5, TimeUnit.SECONDS);
        assertThat(longResult.response().status()).isEqualTo("success");
        assertThat(longResult.response().output()).isEqualTo("real answer");

        SyncInvocation replay = coordinator.invoke(lookup(factory, "key", spec), spec, 10_000)
                .block(Duration.ofSeconds(5));
        assertThat(replay.response().status()).isEqualTo("success");
    }

    @Test
    void anExecutionLevelDeadline_concludesEveryObserverWithTheSameTerminal() throws Exception {
        CompletableFuture<DispatchResult> backend = new CompletableFuture<>();
        CoordinatorHarness h = harnessWithBackend(backend);

        InvocationExecutionFactory.ExecutionLookup first = lookup(h.factory(), "key");
        ExecutionRecord executionRecord = first.executionRecord();
        CompletableFuture<SyncInvocation> owner = h.coordinator().invoke(first, spec("fn"), 10_000).toFuture();
        try {
            // The execution-level deadline (the sync queue's wait expiry) concludes the whole
            // execution: TIMEOUT state, a QUEUE_TIMEOUT result on the shared future, settle.
            executionRecord.markTimeout();
            executionRecord.completion().complete(InvocationResult.error("QUEUE_TIMEOUT", "Queue wait exceeded"));
            h.store().settle(executionRecord);

            // Every remaining observer sees the same terminal: the replay reads TIMEOUT.
            SyncInvocation replay = h.coordinator().invoke(lookup(h.factory(), "key"), spec("fn"), 10_000)
                    .block(Duration.ofSeconds(5));
            assertThat(replay.response().status())
                    .as("an execution-level deadline is a shared terminal every observer sees")
                    .isEqualTo("timeout");

            // A late backend success cannot change the already-archived terminal.
            backend.complete(DispatchResult.warm(InvocationResult.success("late")));
            assertThat(h.store().outcomeOf(executionRecord.executionId()).state())
                    .as("a late response must not overwrite the shared execution-level timeout")
                    .isEqualTo(ExecutionState.TIMEOUT);
        } finally {
            owner.cancel(true);
        }
    }
}
