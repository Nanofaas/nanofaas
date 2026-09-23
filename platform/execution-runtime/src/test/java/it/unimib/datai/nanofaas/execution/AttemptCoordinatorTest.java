package it.unimib.datai.nanofaas.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
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

    private static SchedulingStrategy fakeStrategy(String id) {
        SchedulingStrategy strategy = mock(SchedulingStrategy.class);
        when(strategy.id()).thenReturn(id);
        when(strategy.newIndex()).thenAnswer(invocation -> mock(SchedulingIndex.class));
        return strategy;
    }
}
