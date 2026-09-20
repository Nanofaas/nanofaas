package it.unimib.datai.nanofaas.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.TimeSource;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.RetryScheduler;
import java.time.Instant;
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
                transport, TimeSource.system(), mock(AttemptObserver.class));
        coordinator.dispatch(task.withDispatchLease(lease));
        outcome.complete(DispatchResult.warm(InvocationResult.success("ok")));
        assertThat(lease.isReleased()).isFalse();
        assertThat(capacity.inFlight("fn")).isEqualTo(1);
        drained.complete(null);
        assertThat(lease.isReleased()).isTrue();
        assertThat(capacity.inFlight("fn")).isZero();
    }
}
