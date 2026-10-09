package it.unimib.datai.nanofaas.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.controlplane.service.RetryScheduler;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Task 10 (issue #208), written RED before {@code AttemptCoordinator} exists. This is the
 * brief's own pinning test for the invariant identified while reading {@code
 * ExecutionCompletionHandler}: the LOGICAL outcome future (the attempt's dispatch result) and the
 * PHYSICAL drain (the raw transport's own completion, decoupled via {@code resourcesDrained} in
 * the current code) release the dispatch lease and the retained input independently. A next
 * attempt must never observe capacity as free before the previous attempt's raw transport has
 * actually finished draining — even across an administrative scheduler-strategy switch or a
 * waiter cancellation, neither of which is a drain signal.
 *
 * <p>This class, {@code AttemptCoordinator}, {@code AttemptTransport}, {@code AttemptHandle} and
 * {@code AttemptObserver} do not exist yet, and neither does a {@code DispatchResult} in this
 * module ({@code platform/execution-runtime}) — it is still owned by {@code :control-plane}
 * until a later step moves it here, package and fields preserved. This test is therefore RED by
 * failing to compile, which the task brief explicitly allows as a valid RED for this step.
 */
class AttemptCoordinatorTest {

    @Test
    void executedHandlerErrorRetainsNodeButAdmissionFailureAndStaleProofDoNot() {
        for(boolean executed:List.of(true,false)) {
            var spec=new FunctionSpec("fn","image",null,null,null,30000,1,10,0,null,ExecutionMode.LOCAL,null,null,null);
            var task=new InvocationTask("error-"+executed,"fn",spec,new InvocationRequest("input",null),null,null,Instant.now(),1,InvocationKind.SYNC);
            var record=new ExecutionRecord(task.executionId(),task);
            record.pinPlannedRoute(it.unimib.datai.nanofaas.controlplane.offload.PlannedInvocationRoute.local("edge-b"));
            record.markRunning();
            var store=new ExecutionStore();store.put(record);
            var coordinator=new AttemptCoordinator(store,new FunctionCapacityRegistry(),RetryScheduler.unavailable(),mock(AttemptTransport.class),mock(AttemptObserver.class));
            var result=new DispatchResult(InvocationResult.error(executed?"HANDLER_ERROR":"RUNTIME_HANDLER_BUSY","failed"),false,null,null,executed);
            coordinator.completeExecution(task.executionId(),result,2);
            assertThat(record.executionNode()).isNull();
            coordinator.completeExecution(task.executionId(),result,1);
            assertThat(record.completion().join().success()).isFalse();
            assertThat(record.executionNode()).isEqualTo(executed?"edge-b":null);
            assertThat(record.toOutcome().executionNode()).isEqualTo(executed?"edge-b":null);
        }
    }

    @Test
    void offloadedHandlerErrorKeepsTerminalExecutionIdInOutcomeAndCompletion() {
        var spec = new FunctionSpec("fn", "image", null, null, null,
                30000, 1, 10, 0, null, ExecutionMode.LOCAL, null, null, null);
        var task = new InvocationTask("origin", "fn", spec, new InvocationRequest("input", null),
                null, null, Instant.now(), 1, InvocationKind.SYNC);
        var record = new ExecutionRecord("origin", task);
        var store = new ExecutionStore();
        store.put(record);
        var coordinator = new AttemptCoordinator(store, new FunctionCapacityRegistry(),
                RetryScheduler.unavailable(), mock(AttemptTransport.class), mock(AttemptObserver.class));
        coordinator.completeOffloadedExecution("origin", new InvocationResult(false, null,
                new it.unimib.datai.nanofaas.common.model.ErrorInfo("HANDLER_ERROR", "failed"),
                null, java.util.Map.of("X-NanoFaaS-Terminal-Execution-Id", "remote"), null));
        assertThat(record.toOutcome().headers()).containsEntry("X-NanoFaaS-Terminal-Execution-Id", "remote");
        assertThat(record.completion().join().headers()).containsEntry("X-NanoFaaS-Terminal-Execution-Id", "remote");
        assertThat(record.executionId()).isEqualTo("origin");
    }

    @Test
    void retryHintIsCalculatedOnceAndStaleCompletionCannotReplaceIt() {
        Instant now = Instant.parse("2026-09-24T12:00:00Z");
        var spec = new FunctionSpec("fn", "image", null, null, null,
                30000, 1, 10, 3, null, ExecutionMode.LOCAL, null, null, null);
        var task = new InvocationTask("e1", "fn", spec, new InvocationRequest("in", null),
                null, null, now, 1, InvocationKind.SYNC);
        var record = new ExecutionRecord("e1", task,
                new it.unimib.datai.nanofaas.controlplane.execution.TimeSource(() -> now, () -> 0L));
        var store = new ExecutionStore();
        store.put(record);
        var dueTimes = new java.util.ArrayList<Instant>();
        RetryScheduler retry = (next, due, rejected) -> {
            assertThat(Thread.holdsLock(record)).isFalse();
            assertThat(next.attempt()).isEqualTo(2);
            dueTimes.add(due);
            return true;
        };
        var coordinator = new AttemptCoordinator(store, new FunctionCapacityRegistry(), retry,
                mock(AttemptTransport.class), mock(AttemptObserver.class));
        record.markRunning();
        coordinator.completeExecution("e1", new DispatchResult(
                InvocationResult.error("EXTERNAL_ERROR", "busy"), false, null, now.plusSeconds(1)), 1);
        coordinator.completeExecution("e1", new DispatchResult(
                InvocationResult.error("EXTERNAL_ERROR", "stale"), false, null, now.plusSeconds(30)), 1);
        assertThat(dueTimes).containsExactly(now.plusSeconds(1));
    }

    @Test
    void logicalOutcomeDoesNotReleasePhysicalOwnership() {
        var store = new ExecutionStore();
        var capacity = new FunctionCapacityRegistry();
        var spec = new FunctionSpec("fn", "test-image", null, null, null,
                30000, 1, 10, 0, null, ExecutionMode.LOCAL, null, null, null);
        var task = new InvocationTask("e1", "fn", spec, new InvocationRequest("payload", null),
                null, null, Instant.now(), 1, InvocationKind.SYNC);
        store.put(new ExecutionRecord("e1", task));
        capacity.register("fn", 1);
        var lease = capacity.tryAcquireLease(capacity.activeGeneration("fn"), ignored -> {});
        var outcome = new CompletableFuture<DispatchResult>();
        var drained = new CompletableFuture<Void>();
        AttemptTransport transport = ignored -> new AttemptHandle(outcome, drained, mock(Future.class));
        var coordinator = new AttemptCoordinator(store, capacity, RetryScheduler.unavailable(),
                transport, mock(AttemptObserver.class));
        coordinator.dispatch(task.withDispatchLease(lease));
        outcome.complete(DispatchResult.warm(InvocationResult.success("ok")));
        assertThat(lease.isReleased()).isFalse();
        assertThat(capacity.inFlight("fn")).isEqualTo(1);
        drained.complete(null);
        assertThat(lease.isReleased()).isTrue();
        assertThat(capacity.inFlight("fn")).isZero();
    }

    /**
     * Brief line 65: the same two lease assertions, but with a REAL {@code SchedulerEngine}
     * strategy switch interleaved between completing the logical outcome and completing the
     * physical drain — exactly the interleaving a hot swap creates, and the one axis the
     * original test above does not exercise.
     *
     * <p>The engine here is independent of this attempt's own dispatch (which never goes
     * through it — {@code coordinator.dispatch} attaches the lease directly, no engine ticket
     * involved) and its {@code PendingWorkStore} is empty, so {@code switchTo} takes the
     * uncontested path: validate, take the gate, rebuild an empty candidate index, publish it,
     * discard the old one. Nothing in that path reads or writes {@link FunctionCapacityRegistry}
     * or the {@link ExecutionRecord} this attempt owns — the two subsystems only ever share the
     * registry, and a switch never touches it. That is exactly the property under test: an
     * administrative action entirely unrelated to this attempt's physical capacity must not be
     * able to race its release.
     *
     * <p>Falsifiable the way step 1b requires: if the lease/drain decoupling were lost — e.g. if
     * completing {@code outcome} released the lease directly, instead of waiting on {@code
     * drained} — the FIRST pair of assertions below (immediately after {@code outcome.complete}
     * and the switch) would already see {@code lease.isReleased() == true} and {@code inFlight ==
     * 0}, and the test would fail right there, before {@code drained} is ever completed. Nothing
     * about the switch is needed to make it fail in that scenario; the switch's only job here is
     * to prove the failure mode above cannot be masked by "nothing else happened between outcome
     * and drained" — with a real, unrelated engine action landing in that exact window instead.
     */
    @Test
    void logicalOutcomeDoesNotReleasePhysicalOwnershipAcrossAStrategySwitch() {
        var store = new ExecutionStore();
        var capacity = new FunctionCapacityRegistry();
        var spec = new FunctionSpec("fn", "test-image", null, null, null,
                30000, 1, 10, 0, null, ExecutionMode.LOCAL, null, null, null);
        var task = new InvocationTask("e1", "fn", spec, new InvocationRequest("payload", null),
                null, null, Instant.now(), 1, InvocationKind.SYNC);
        store.put(new ExecutionRecord("e1", task));
        capacity.register("fn", 1);
        var lease = capacity.tryAcquireLease(capacity.activeGeneration("fn"), ignored -> {});
        var outcome = new CompletableFuture<DispatchResult>();
        var drained = new CompletableFuture<Void>();
        AttemptTransport transport = ignored -> new AttemptHandle(outcome, drained, mock(Future.class));
        var coordinator = new AttemptCoordinator(store, capacity, RetryScheduler.unavailable(),
                transport, mock(AttemptObserver.class));
        coordinator.dispatch(task.withDispatchLease(lease));

        var engine = new SchedulerEngine(new PendingWorkStore(64),
                new StrategyRegistry(List.of(fakeStrategy("per-function"), fakeStrategy("shared-queue"))),
                "per-function", mock(EngineDispatch.class), mock(EngineReadiness.class),
                generation -> true, Clock.systemUTC(), System::nanoTime);

        outcome.complete(DispatchResult.warm(InvocationResult.success("ok")));
        engine.switchTo("shared-queue");
        assertThat(lease.isReleased()).isFalse();
        assertThat(capacity.inFlight("fn")).isEqualTo(1);
        drained.complete(null);
        assertThat(lease.isReleased()).isTrue();
        assertThat(capacity.inFlight("fn")).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 3})
    void retryBudgetCountsAdditionalAttempts(int maxRetries) {
        var fixture = new RetryFixture(maxRetries);
        for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
            fixture.record.markRunning();
            fixture.coordinator.completeExecution("retry", DispatchResult.warm(
                    InvocationResult.error("EXTERNAL_ERROR", "busy")), attempt);
        }
        assertThat(fixture.record.completion().join().error().code()).isEqualTo("EXTERNAL_ERROR");
        assertThat(fixture.dueTimes).hasSize(maxRetries);
        assertThat(fixture.draws).hasValue(maxRetries);
    }

    @Test
    void duplicateLateRefusalConcludesOnceWithNoAttemptMeasurements() {
        var fixture = new RetryFixture(3);
        fixture.fail();
        fixture.refusal.get().run();
        fixture.refusal.get().run();
        assertThat(fixture.record.completion().join().error().code()).isEqualTo("EXTERNAL_ERROR");
        org.mockito.Mockito.verify(fixture.observer).retried(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(fixture.observer).completed(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(AttemptObserver.NO_ATTEMPT),
                org.mockito.ArgumentMatchers.eq(AttemptObserver.NO_ATTEMPT));
        org.mockito.Mockito.verifyNoInteractions(fixture.transport);
        assertThat(fixture.draws).hasValue(1);
    }

    @Test
    void simultaneousCallbackAndHttpCompletionSelectOneDueTime() throws Exception {
        var fixture = new RetryFixture(3);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { awaitBarrier(barrier); fixture.fail(); });
            var second = pool.submit(() -> { awaitBarrier(barrier); fixture.fail(); });
            first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            second.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(fixture.dueTimes).hasSize(1);
        assertThat(fixture.draws).hasValue(1);
        assertThat(fixture.record.task().attempt()).isEqualTo(2);
    }

    @Test
    void waitingRetryCannotReleaseAnUndrainedPhysicalLease() {
        var fixture = new RetryFixture(3);
        fixture.capacity.register("fn", 1);
        var lease = fixture.capacity.tryAcquireLease("fn", 1);
        var outcome = new CompletableFuture<DispatchResult>();
        var drained = new CompletableFuture<Void>();
        when(fixture.transport.submit(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AttemptHandle(outcome, drained, mock(Future.class)));
        fixture.coordinator.dispatch(fixture.record.task().withDispatchLease(lease));
        // Callback completion may arrive independently of the still-running transport.
        fixture.fail();
        assertThat(fixture.dueTimes).hasSize(1);
        assertThat(lease.isReleased()).isFalse();
        assertThat(fixture.capacity.inFlight("fn")).isEqualTo(1);
        drained.complete(null);
        assertThat(lease.isReleased()).isTrue();
        outcome.complete(DispatchResult.warm(InvocationResult.success("stale")));
        assertThat(fixture.record.completion()).isNotDone();
    }

    private static void awaitBarrier(java.util.concurrent.CyclicBarrier barrier) {
        try { barrier.await(5, java.util.concurrent.TimeUnit.SECONDS); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static final class RetryFixture {
        final java.util.concurrent.atomic.AtomicInteger draws = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<Runnable> refusal = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.List<Instant> dueTimes = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AttemptObserver observer = mock(AttemptObserver.class);
        final AttemptTransport transport = mock(AttemptTransport.class);
        final FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        final ExecutionRecord record;
        final AttemptCoordinator coordinator;

        RetryFixture(int maxRetries) {
            Instant now = Instant.parse("2026-09-24T12:00:00Z");
            var spec = new FunctionSpec("fn", "image", null, null, null,
                    30000, 1, 10, maxRetries, null, ExecutionMode.LOCAL, null, null, null);
            var task = new InvocationTask("retry", "fn", spec, new InvocationRequest("in", null),
                    null, null, now, 1, InvocationKind.SYNC);
            record = new ExecutionRecord("retry", task,
                    new it.unimib.datai.nanofaas.controlplane.execution.TimeSource(() -> now, () -> 0L));
            var store = new ExecutionStore();
            store.put(record);
            coordinator = new AttemptCoordinator(store, capacity, (next, due, rejected) -> {
                dueTimes.add(due);
                refusal.set(rejected);
                return true;
            }, transport, observer, new RetryBackoff(java.time.Duration.ofMillis(100),
                    java.time.Duration.ofSeconds(2), () -> { draws.incrementAndGet(); return 0.0; }));
        }

        void fail() {
            coordinator.completeExecution("retry", DispatchResult.warm(
                    InvocationResult.error("EXTERNAL_ERROR", "busy")), 1);
        }
    }

    private static SchedulingStrategy fakeStrategy(String id) {
        SchedulingStrategy strategy = mock(SchedulingStrategy.class);
        when(strategy.id()).thenReturn(id);
        when(strategy.newIndex()).thenAnswer(invocation -> mock(SchedulingIndex.class));
        return strategy;
    }
}
