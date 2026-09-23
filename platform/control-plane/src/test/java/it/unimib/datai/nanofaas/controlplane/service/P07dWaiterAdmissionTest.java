package it.unimib.datai.nanofaas.controlplane.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacity;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationQuotaExceededException;
import it.unimib.datai.nanofaas.controlplane.capacity.WaiterCapacity;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.dispatch.LocalDispatcher;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.input.RetainedInputEstimator;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class P07dWaiterAdmissionTest {

    @Test
    void productionConstructorsCannotBypassFiniteWaiterCapacity() {
        assertThat(Arrays.stream(ReactiveInvocationCoordinator.class.getConstructors()))
                .allSatisfy(constructor -> assertThat(constructor.getParameterTypes())
                        .contains(WaiterCapacity.class));
        assertThat(Arrays.stream(InvocationService.class.getConstructors()))
                .allSatisfy(constructor -> assertThat(Arrays.stream(constructor.getParameterTypes())
                        .anyMatch(type -> type.equals(WaiterCapacity.class)
                                || type.equals(ReactiveInvocationCoordinator.class))).isTrue());
    }

    @Test
    void massivePendingReplaySaturatesWaitersWithoutRedispatchOrRetainedRejections() throws Exception {
        CompletableFuture<DispatchResult> backend = new CompletableFuture<>();
        AtomicInteger dispatches = new AtomicInteger();
        Harness h = directHarness(2, 2, task -> {
            dispatches.incrementAndGet();
            return backend;
        });

        var firstLookup = h.lookup("fn", "key");
        CompletableFuture<SyncInvocation> first = h.coordinator.invoke(firstLookup, spec("fn"), 10_000).toFuture();
        CompletableFuture<SyncInvocation> second =
                h.coordinator.invoke(h.lookup("fn", "key"), spec("fn"), 10_000).toFuture();

        long executionOwners = h.invocations.executionReservedGlobally();
        long inputBytes = h.invocations.inputReservedGlobally();
        int liveExecutions = h.store.inFlightCount();
        int archivedOutcomes = h.store.size();

        for (int i = 0; i < 100; i++) {
            assertThatThrownBy(() ->
                    h.coordinator.invoke(h.lookup("fn", "key"), spec("fn"), 10_000))
                    .isInstanceOfSatisfying(InvocationQuotaExceededException.class,
                            failure -> assertThat(failure.resource())
                                    .isEqualTo(InvocationQuotaExceededException.Resource.WAITER));
            assertThat(h.invocations.executionReservedGlobally()).isEqualTo(executionOwners);
            assertThat(h.invocations.inputReservedGlobally()).isEqualTo(inputBytes);
            assertThat(h.store.inFlightCount()).isEqualTo(liveExecutions);
            assertThat(h.store.size()).isEqualTo(archivedOutcomes);
        }
        assertThat(dispatches).hasValue(1);
        assertThat(h.waiters.reservedGlobally()).isEqualTo(2);
        assertThat(h.waiters.retainedWaiters()).isEqualTo(2);
        assertThat(executionOwners).isOne();
        assertThat(inputBytes).isPositive();
        assertThat(liveExecutions).isOne();
        assertThat(archivedOutcomes).isZero();

        first.cancel(true);
        assertThat(h.waiters.reservedGlobally()).isOne();
        backend.complete(DispatchResult.warm(InvocationResult.success("shared")));
        assertThat(second.get(2, TimeUnit.SECONDS).response().output()).isEqualTo("shared");
        assertThat(h.waiters.reservedGlobally()).isZero();
        assertThat(h.waiters.retainedWaiters()).isZero();
        assertThat(h.invocations.executionReservedGlobally()).isZero();
        assertThat(h.invocations.inputReservedGlobally()).isZero();
        assertThat(h.store.inFlightCount()).isZero();
        assertThat(h.store.size()).isOne();
    }

    @Test
    void newExecutionIsPublishedBeforeWaiterAdmissionAndRolledBackWhenTheWaiterIsRefused() {
        CompletableFuture<DispatchResult> backend = new CompletableFuture<>();
        Harness h = directHarness(1, 1, ignored -> backend);
        CompletableFuture<SyncInvocation> occupying =
                h.coordinator.invoke(h.lookup("other", "occupying-key"), spec("other"), 10_000).toFuture();
        long occupyingInputBytes = h.invocations.inputReservedGlobally();

        var newLookup = h.lookup("fn", "new-key");
        String newExecutionId = newLookup.executionRecord().executionId();
        assertThat(h.store.getOrNull(newExecutionId)).isSameAs(newLookup.executionRecord());
        assertThat(h.store.inFlightCount()).isEqualTo(2);
        assertThat(h.invocations.executionReservedGlobally()).isEqualTo(2);
        assertThat(h.invocations.inputReservedGlobally()).isGreaterThan(occupyingInputBytes);

        assertThatThrownBy(() -> h.coordinator.invoke(newLookup, spec("fn"), 10_000))
                .isInstanceOfSatisfying(InvocationQuotaExceededException.class,
                        failure -> assertThat(failure.resource())
                                .isEqualTo(InvocationQuotaExceededException.Resource.WAITER));

        assertThat(h.store.getOrNull(newExecutionId)).isNull();
        assertThat(h.store.inFlightCount()).isOne();
        assertThat(h.invocations.executionReservedGlobally()).isOne();
        assertThat(h.invocations.inputReservedGlobally()).isEqualTo(occupyingInputBytes);
        assertThat(h.waiters.reservedGlobally()).isOne();

        occupying.cancel(true);
        backend.complete(DispatchResult.warm(InvocationResult.success("drained")));
        assertThat(h.waiters.reservedGlobally()).isZero();
        assertThat(h.invocations.executionReservedGlobally()).isZero();
        assertThat(h.invocations.inputReservedGlobally()).isZero();
    }

    @Test
    void divergentDeadlinesDetachOnlyTheShortWaiter() throws Exception {
        CompletableFuture<DispatchResult> backend = new CompletableFuture<>();
        Harness h = directHarness(2, 2, ignored -> backend);
        CompletableFuture<SyncInvocation> longWaiter =
                h.coordinator.invoke(h.lookup("fn", "key"), spec("fn"), 10_000).toFuture();

        SyncInvocation shortResult = h.coordinator
                .invoke(h.lookup("fn", "key"), spec("fn"), 1)
                .block(java.time.Duration.ofSeconds(2));

        assertThat(shortResult.response().status()).isEqualTo("timeout");
        assertThat(h.waiters.reservedGlobally()).isOne();
        backend.complete(DispatchResult.warm(InvocationResult.success("long-result")));
        backend.complete(DispatchResult.warm(InvocationResult.success("duplicate")));
        assertThat(longWaiter.get(2, TimeUnit.SECONDS).response().output()).isEqualTo("long-result");
        assertThat(h.waiters.reservedGlobally()).isZero();
        assertThat(h.waiters.retainedWaiters()).isZero();
    }

    @Test
    void archivedReplayStillRequiresTransientWaiterAdmission() {
        CompletableFuture<DispatchResult> pendingBackend = new CompletableFuture<>();
        Harness h = directHarness(1, 1, ignored -> pendingBackend);
        var terminal = h.lookup("fn", "terminal-key");
        terminal.publishAdmission();
        terminal.executionRecord().markSuccess("archived");
        h.store.settle(terminal.executionRecord());

        CompletableFuture<SyncInvocation> occupying =
                h.coordinator.invoke(h.lookup("other", "pending-key"), spec("other"), 10_000).toFuture();
        var lookup = h.lookup("fn", "terminal-key");
        var fnSpec = spec("fn");
        assertThatThrownBy(() -> h.coordinator.invoke(lookup, fnSpec, 10_000).block()) // NOSONAR (java:S5778): invoke() itself throws synchronously on this path
                .isInstanceOf(InvocationQuotaExceededException.class);
        assertThat(h.waiters.retainedWaiters()).isOne();

        occupying.cancel(true);
        SyncInvocation replay = h.coordinator
                .invoke(h.lookup("fn", "terminal-key"), spec("fn"), 10_000).block();
        assertThat(replay.response().output()).isEqualTo("archived");
        assertThat(h.waiters.reservedGlobally()).isZero();
        assertThat(h.waiters.retainedWaiters()).isZero();
    }

    @Test
    void globalAndPerFunctionCapsApplyBeforeDirectAsyncAndSyncAttachments() {
        CompletableFuture<DispatchResult> backend = new CompletableFuture<>();
        Harness direct = directHarness(2, 1, ignored -> backend);
        assertStrategySaturatesAndDrains(direct);

        Harness async = queuedHarness(false);
        assertStrategySaturatesAndDrains(async);

        Harness sync = queuedHarness(true);
        assertStrategySaturatesAndDrains(sync);
    }

    @Test
    void enqueuePublicationFailureRollsBackWaiterExecutionAndInput() {
        FunctionCapacityRegistry generations = generations("fn");
        InvocationEnqueuer failing = new InvocationEnqueuer() {
            @Override public boolean enqueue(InvocationTask task) { throw new AssertionError("enqueue failed"); }
            @Override public boolean supportsAsync() { return true; }
            @Override public QueueStrategy queueStrategy() { return QueueStrategy.FUNCTION_QUEUE; }
        };
        Harness h = harness(generations, 2, 2, failing, null, new LocalDispatcher());
        var lookup = h.lookup("fn", "new-key");

        var fnSpec = spec("fn");
        assertThatThrownBy(() -> h.coordinator.invoke(lookup, fnSpec, 10_000).block()) // NOSONAR (java:S5778): invoke() itself throws synchronously on this path
                .isInstanceOf(AssertionError.class)
                .hasMessage("enqueue failed");
        assertThat(h.waiters.reservedGlobally()).isZero();
        assertThat(h.waiters.retainedWaiters()).isZero();
        assertThat(h.invocations.executionReservedGlobally()).isZero();
        assertThat(h.invocations.inputReservedGlobally()).isZero();
        assertThat(h.store.getOrNull(lookup.executionRecord().executionId())).isNull();
    }

    @Test
    void oldGenerationCompletionAndDoubleCancelCannotReleaseReplacementWaiter() {
        FunctionCapacityRegistry generations = generations("fn");
        Harness h = harness(generations, 2, 2, acceptingEnqueuer(), null, new LocalDispatcher());
        var oldLookup = h.lookup("fn", null);
        CompletableFuture<SyncInvocation> oldWaiter =
                h.coordinator.invoke(oldLookup, spec("fn"), 10_000).toFuture();

        generations.remove("fn");
        generations.register("fn", 1);
        var replacementLookup = h.lookup("fn", null);
        CompletableFuture<SyncInvocation> replacementWaiter =
                h.coordinator.invoke(replacementLookup, spec("fn"), 10_000).toFuture();
        assertThat(h.waiters.reservedGlobally()).isEqualTo(2);

        h.completion.completeExecution(oldLookup.executionRecord().executionId(),
                DispatchResult.warm(InvocationResult.success("late-old")));
        assertThat(oldWaiter.join().response().output()).isEqualTo("late-old");
        h.completion.completeExecution(oldLookup.executionRecord().executionId(),
                DispatchResult.warm(InvocationResult.success("duplicate-old")));
        oldWaiter.cancel(true);
        oldWaiter.cancel(true);
        assertThat(h.waiters.reservedGlobally()).isOne();
        assertThat(h.waiters.reservedForFunction("fn")).isOne();

        replacementWaiter.cancel(true);
        assertThat(h.waiters.reservedGlobally()).isZero();
        assertThat(h.waiters.retainedWaiters()).isZero();
    }

    @Test
    void cancellationRacingCompletionDetachesExactlyOnce() throws Exception {
        CompletableFuture<DispatchResult> backend = new CompletableFuture<>();
        Harness h = directHarness(1, 1, ignored -> backend);
        CompletableFuture<SyncInvocation> waiter =
                h.coordinator.invoke(h.lookup("fn", null), spec("fn"), 10_000).toFuture();
        CountDownLatch start = new CountDownLatch(1);
        Thread cancel = Thread.ofVirtual().start(() -> {
            await(start);
            waiter.cancel(true);
        });
        Thread complete = Thread.ofVirtual().start(() -> {
            await(start);
            backend.complete(DispatchResult.warm(InvocationResult.success("done")));
        });

        start.countDown();
        cancel.join();
        complete.join();

        assertThat(h.waiters.reservedGlobally()).isZero();
        assertThat(h.waiters.reservedForFunction("fn")).isZero();
        assertThat(h.waiters.retainedWaiters()).isZero();
    }

    private static void assertStrategySaturatesAndDrains(Harness h) {
        CompletableFuture<SyncInvocation> first =
                h.coordinator.invoke(h.lookup("fn", null), spec("fn"), 10_000).toFuture();
        assertThatThrownBy(() ->
                h.coordinator.invoke(h.lookup("fn", null), spec("fn"), 10_000))
                .isInstanceOfSatisfying(InvocationQuotaExceededException.class,
                        failure -> assertThat(failure.resource())
                                .isEqualTo(InvocationQuotaExceededException.Resource.WAITER));
        CompletableFuture<SyncInvocation> second =
                h.coordinator.invoke(h.lookup("other", null), spec("other"), 10_000).toFuture();
        var third = h.lookup("third", null);
        var thirdSpec = spec("third");
        assertThatThrownBy(() -> h.coordinator.invoke(third, thirdSpec, 10_000))
                .isInstanceOf(InvocationQuotaExceededException.class);
        assertThat(h.waiters.reservedGlobally()).isEqualTo(2);
        assertThat(h.waiters.reservedForFunction("fn")).isOne();
        assertThat(h.waiters.reservedForFunction("other")).isOne();
        assertThat(h.waiters.retainedWaiters()).isEqualTo(2);
        first.cancel(true);
        second.cancel(true);
        assertThat(h.waiters.reservedGlobally()).isZero();
        assertThat(h.waiters.retainedWaiters()).isZero();
    }

    private static Harness directHarness(long globalWaiters, long perFunctionWaiters,
                                         Function<InvocationTask, CompletableFuture<DispatchResult>> dispatch) {
        FunctionCapacityRegistry generations = generations("fn", "other", "third");
        return harness(generations, globalWaiters, perFunctionWaiters,
                InvocationEnqueuer.noOp(), null, new LocalDispatcher() {
                    @Override
                    public CompletableFuture<DispatchResult> dispatch(InvocationTask task) {
                        return dispatch.apply(task);
                    }
                });
    }

    private static Harness queuedHarness(boolean sync) {
        FunctionCapacityRegistry generations = generations("fn", "other", "third");
        SyncQueueGateway gateway = sync ? new SyncQueueGateway() {
            @Override public void enqueueOrThrow(InvocationTask task) { /* no-op: this test double ignores the call */ }
            @Override public boolean enabled() { return true; }
            @Override public int retryAfterSeconds() { return 1; }
        } : null;
        return harness(generations, 2, 1, acceptingEnqueuer(), gateway, new LocalDispatcher());
    }

    private static InvocationEnqueuer acceptingEnqueuer() {
        return new InvocationEnqueuer() {
            @Override public boolean enqueue(InvocationTask task) { return true; }
            @Override public boolean supportsAsync() { return true; }
            @Override public QueueStrategy queueStrategy() { return QueueStrategy.FUNCTION_QUEUE; }
        };
    }

    private static Harness harness(FunctionCapacityRegistry generations,
                                   long globalWaiters,
                                   long perFunctionWaiters,
                                   InvocationEnqueuer enqueuer,
                                   SyncQueueGateway syncGateway,
                                   LocalDispatcher dispatcher) {
        ExecutionStore store = new ExecutionStore();
        Metrics metrics = new Metrics(new SimpleMeterRegistry());
        InvocationCapacity invocations = new InvocationCapacity(
                generations, 20, 20, 1_000_000, 1_000_000, 16);
        WaiterCapacity waiters = new WaiterCapacity(generations, globalWaiters, perFunctionWaiters);
        InvocationExecutionFactory factory = new InvocationExecutionFactory(
                store, new IdempotencyStore(), metrics, invocations,
                new RetainedInputEstimator.Limits(12, 128, 1_024, 64 * 1_024));
        ExecutionCompletionHandler completion = new ExecutionCompletionHandler(
                store, enqueuer::enqueue, new DispatcherRouter(dispatcher, null), metrics, null, generations);
        ReactiveInvocationCoordinator coordinator = new ReactiveInvocationCoordinator(
                enqueuer, metrics, syncGateway, null, completion, new InvocationResponseMapper(), waiters);
        return new Harness(store, factory, invocations, waiters, completion, coordinator);
    }

    private static FunctionCapacityRegistry generations(String... names) {
        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        for (String name : names) {
            generations.register(name, 1);
        }
        return generations;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static FunctionSpec spec(String name) {
        return new FunctionSpec(name, "img", List.of(), Map.of(), null,
                10_000, 1, 10, 0, null, ExecutionMode.LOCAL, RuntimeMode.HTTP,
                null, null, null);
    }

    private record Harness(ExecutionStore store,
                           InvocationExecutionFactory factory,
                           InvocationCapacity invocations,
                           WaiterCapacity waiters,
                           ExecutionCompletionHandler completion,
                           ReactiveInvocationCoordinator coordinator) {
        InvocationExecutionFactory.ExecutionLookup lookup(String functionName, String key) {
            return factory.createOrReuseExecution(functionName, spec(functionName),
                    new InvocationRequest("payload", Map.of()), key, null, InvocationKind.SYNC);
        }
    }
}
