package it.unimib.datai.nanofaas.modules.syncqueue.scheduler;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacity;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.dispatch.LocalDispatcher;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.input.CanonicalInvocationInput;
import it.unimib.datai.nanofaas.controlplane.input.RetainedInputEstimator;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.service.ExecutionCompletionHandler;
import it.unimib.datai.nanofaas.controlplane.service.InvocationExecutionFactory;
import it.unimib.datai.nanofaas.controlplane.service.Metrics;
import it.unimib.datai.nanofaas.execution.EngineDispatch;
import it.unimib.datai.nanofaas.execution.PendingEntry;
import it.unimib.datai.nanofaas.execution.PendingWorkStore;
import it.unimib.datai.nanofaas.execution.SchedulerEngine;
import it.unimib.datai.nanofaas.execution.StrategyRegistry;
import it.unimib.datai.nanofaas.modules.syncqueue.SharedQueueSchedulingStrategy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P07c: input backpressure under a real, saturated {@link InvocationCapacity}.
 *
 * <p>Task 13b (issue #208) moved this file off the module's own {@code SyncScheduler}: the
 * composed {@link SchedulerEngine} is the product now, so the acceptance is proved on it. Two of
 * the three tests this file used to hold are deleted rather than migrated, because the engine
 * pins their property directly and more sharply:
 *
 * <ul>
 *   <li>"input saturation requeues instead of settling a function failure" -
 *       {@code SchedulerEngineDispatchTest.exhaustedInputQuotaKeepsTheReservationAndRequeues}
 *       asserts the reservation is kept, the ticket re-added to the index and no rejection
 *       callback made.</li>
 *   <li>"a reserved dispatch prevents concurrent admission from displacing the backpressured
 *       item" - {@code SchedulerEngineDispatchTest
 *       .aConcurrentEnqueueCannotTakeTheSlotReservedByAnInFlightSubmit}.</li>
 * </ul>
 *
 * <p>The remaining test is the one whose subject is not the scheduler at all but the real
 * physical-copy reservation: it keeps the saturated {@link InvocationCapacity} and the real
 * {@link ExecutionCompletionHandler}, and replaces only the retired loop with the engine.
 */
class P07cInputBackpressureTest {

    private static final RetainedInputEstimator.Limits INPUT_LIMITS =
            new RetainedInputEstimator.Limits(12, 128, 1_024, 64 * 1_024);

    @Test
    void realPhysicalCopyLargerThanHalfQuotaRemainsQueuedUnderInputSaturation() {
        InvocationRequest request = new InvocationRequest(
                new ArrayList<>(List.of("payload")), Map.of());
        long canonicalBytes = ((CanonicalInvocationInput.Accepted)
                CanonicalInvocationInput.canonicalize(request, INPUT_LIMITS)).retainedBytes();
        long inputQuota = canonicalBytes * 2 - 1;
        assertThat(canonicalBytes).isGreaterThan(inputQuota / 2);

        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        generations.register("fn", 1);
        InvocationCapacity capacity = new InvocationCapacity(
                generations, 4, 4,
                inputQuota, inputQuota,
                canonicalBytes - 1, canonicalBytes - 1, 16);
        ExecutionStore store = new ExecutionStore();
        Metrics coreMetrics = new Metrics(new SimpleMeterRegistry(), generations);
        coreMetrics.registerFunction("fn");
        InvocationExecutionFactory factory = new InvocationExecutionFactory(
                store, new IdempotencyStore(), coreMetrics, capacity, INPUT_LIMITS);

        InvocationExecutionFactory.ExecutionLookup lookup = factory.createOrReuseExecution(
                "fn", functionSpec(), request, null, null, InvocationKind.SYNC);
        InvocationTask queuedTask = lookup.executionRecord().prepareForQueue();

        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, null,
                new DispatcherRouter(new LocalDispatcher() {
                    @Override
                    public CompletableFuture<DispatchResult> dispatch(InvocationTask task) {
                        return CompletableFuture.completedFuture(
                                DispatchResult.warm(
                                        it.unimib.datai.nanofaas.common.model.InvocationResult.success("unused")));
                    }
                }, null),
                coreMetrics, null, generations);

        // The engine's transport is the real completion handler: it is what refuses the physical
        // copy reservation, and SchedulerEngine.submit is what turns that refusal into a requeue.
        EngineDispatch dispatch = new EngineDispatch() {
            @Override
            public DispatchOwnership tryAcquire(SchedulingTicket ticket) {
                return generations.tryAcquireLease(ticket.generation(), ignored -> { });
            }

            @Override
            public boolean isCurrent(SchedulingTicket ticket) {
                return true;
            }

            @Override
            public void submit(InvocationTask task) {
                handler.dispatch(task);
            }

            @Override
            public void expired(InvocationTask task) {
                store.expired(task);
            }

            @Override
            public void removed(InvocationTask task) {
                store.removed(task);
            }

            @Override
            public void rejected(InvocationTask task, Throwable failure) {
                store.rejected(task, failure);
            }
        };
        SchedulingStrategy strategy = new SharedQueueSchedulingStrategy();
        PendingWorkStore pending = new PendingWorkStore(16);
        SchedulerEngine engine = new SchedulerEngine(pending, new StrategyRegistry(List.of(strategy)),
                strategy.id(), dispatch, generation -> true,
                generation -> generation.equals(generations.activeGeneration(generation.functionName())),
                Clock.systemUTC(), System::nanoTime);

        SchedulingTicket ticket = new SchedulingTicket(
                new it.unimib.datai.nanofaas.controlplane.scheduler.TicketId(
                        queuedTask.executionId(), queuedTask.attempt()),
                generations.activeGeneration("fn"), 0, Instant.now(), Instant.now(), null);
        assertThat(engine.enqueue(new PendingEntry(ticket, queuedTask))).isTrue();

        engine.tick();

        // The item is back in the index, still admitted and not concluded, and the capacity
        // accounting says exactly which reservation is still held: the canonical input, never the
        // physical copy the saturated quota refused.
        assertThat(pending.pendingCount()).isOne();
        assertThat(lookup.executionRecord().state()).isEqualTo(ExecutionState.QUEUED);
        assertThat(lookup.executionRecord().completion()).isNotDone();
        assertThat(capacity.executionReservedGlobally()).isOne();
        assertThat(capacity.inputReservedGlobally()).isEqualTo(canonicalBytes);
        assertThat(capacity.physicalInputCopyReservedGlobally()).isZero();

        // Draining the queued ticket releases everything it was holding.
        engine.removeAllFor("fn");
        lookup.abandonAdmission();
        assertThat(capacity.executionReservedGlobally()).isZero();
        assertThat(capacity.inputReservedGlobally()).isZero();
        assertThat(capacity.physicalInputCopyReservedGlobally()).isZero();
    }

    private static FunctionSpec functionSpec() {
        return new FunctionSpec("fn", "image", null, Map.of(), null,
                1_000, 1, 10, 0, null, ExecutionMode.LOCAL, null, null, null);
    }
}
