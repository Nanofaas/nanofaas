package it.unimib.datai.nanofaas.modules.syncqueue.scheduler;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationQuotaExceededException;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacity;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.dispatch.LocalDispatcher;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.controlplane.service.ExecutionCompletionHandler;
import it.unimib.datai.nanofaas.controlplane.service.InvocationExecutionFactory;
import it.unimib.datai.nanofaas.controlplane.service.Metrics;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.input.CanonicalInvocationInput;
import it.unimib.datai.nanofaas.controlplane.input.RetainedInputEstimator;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport;
import it.unimib.datai.nanofaas.modules.syncqueue.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueMetrics;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class P07cInputBackpressureTest {
    private static final RetainedInputEstimator.Limits INPUT_LIMITS =
            new RetainedInputEstimator.Limits(12, 128, 1_024, 64 * 1_024);

    @Test
    void physicalCopySaturationRequeuesInsteadOfSettlingAFunctionFailure() {
        InvocationEnqueuer enqueuer = SchedulerLeaseTestSupport.enqueuer();
        when(enqueuer.hasAvailableSlot("fn")).thenReturn(true);
        when(enqueuer.tryAcquireSlot("fn")).thenReturn(true);
        ExecutionStore store = new ExecutionStore();
        SyncQueueService queue = queue(store);
        InvocationTask task = task();
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        store.put(record);
        queue.enqueueOrThrow(task);
        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, ignored -> {
            throw new InvocationQuotaExceededException(InvocationQuotaExceededException.Resource.INPUT);
        });

        scheduler.tickOnce();

        assertThat(queue.queuedItems()).isOne();
        assertThat(record.state()).isEqualTo(ExecutionState.QUEUED);
        assertThat(record.completion()).isNotDone();
    }

    @Test
    void dispatchReservationPreventsConcurrentAdmissionFromDisplacingBackpressuredItem() {
        InvocationEnqueuer enqueuer = SchedulerLeaseTestSupport.enqueuer();
        when(enqueuer.hasAvailableSlot("fn")).thenReturn(true);
        when(enqueuer.tryAcquireSlot("fn")).thenReturn(true);
        ExecutionStore store = new ExecutionStore();
        SyncQueueService queue = queue(store, 1);
        InvocationTask first = task("first");
        InvocationTask second = task("second");
        store.put(new ExecutionRecord(first.executionId(), first));
        queue.enqueueOrThrow(first);
        AtomicBoolean secondRejected = new AtomicBoolean();
        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, ignored -> {
            try {
                queue.enqueueOrThrow(second);
            } catch (it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectedException expected) {
                secondRejected.set(true);
            }
            throw new InvocationQuotaExceededException(InvocationQuotaExceededException.Resource.INPUT);
        });

        scheduler.tickOnce();

        assertThat(secondRejected).isTrue();
        assertThat(queue.queuedItems()).isOne();
        assertThat(queue.pollReady(Instant.now()).task().executionId()).isEqualTo("first");
    }

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
        FunctionSpec spec = functionSpec();
        InvocationExecutionFactory.ExecutionLookup lookup = factory.createOrReuseExecution(
                "fn", spec, request, null, null, InvocationKind.SYNC);
        InvocationTask queuedTask = lookup.executionRecord().prepareForQueue();
        SyncQueueService queue = queue(store);
        queue.enqueueOrThrow(queuedTask);
        InvocationEnqueuer enqueuer = SchedulerLeaseTestSupport.enqueuer();
        when(enqueuer.hasAvailableSlot("fn")).thenReturn(true);
        when(enqueuer.tryAcquireSlot("fn")).thenReturn(true);
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, enqueuer,
                new DispatcherRouter(new LocalDispatcher() {
                    @Override
                    public CompletableFuture<DispatchResult> dispatch(InvocationTask task) {
                        return CompletableFuture.completedFuture(
                                DispatchResult.warm(it.unimib.datai.nanofaas.common.model.InvocationResult.success("unused")));
                    }
                }, null),
                coreMetrics, null, generations);
        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, handler::dispatch);

        scheduler.tickOnce();

        assertThat(queue.queuedItems()).isOne();
        assertThat(lookup.executionRecord().state()).isEqualTo(ExecutionState.QUEUED);
        assertThat(lookup.executionRecord().completion()).isNotDone();
        assertThat(capacity.executionReservedGlobally()).isOne();
        assertThat(capacity.inputReservedGlobally()).isEqualTo(canonicalBytes);
        assertThat(capacity.physicalInputCopyReservedGlobally()).isZero();

        queue.pollReady(Instant.now()).task().releaseQueuedInput();
        lookup.abandonAdmission();
        assertThat(capacity.executionReservedGlobally()).isZero();
        assertThat(capacity.inputReservedGlobally()).isZero();
        assertThat(capacity.physicalInputCopyReservedGlobally()).isZero();
    }

    private static SyncQueueService queue(ExecutionStore store) {
        return queue(store, 10);
    }

    private static SyncQueueService queue(ExecutionStore store, int maxDepth) {
        SyncQueueProperties properties = new SyncQueueProperties(
                true, false, maxDepth, Duration.ofSeconds(2), Duration.ofSeconds(2), 2,
                Duration.ofSeconds(30), 3);
        return new SyncQueueService(properties, store,
                new SyncQueueMetrics(new SimpleMeterRegistry()),
                SyncQueueConfigSource.fixed(properties.runtimeDefaults()));
    }

    private static InvocationTask task() {
        return task("exec");
    }

    private static InvocationTask task(String executionId) {
        FunctionSpec spec = functionSpec();
        return new InvocationTask(executionId, "fn", spec,
                new InvocationRequest("payload", Map.of()), null, null, Instant.now(), 1,
                InvocationKind.SYNC);
    }

    private static FunctionSpec functionSpec() {
        return new FunctionSpec("fn", "image", null, Map.of(), null,
                1_000, 1, 10, 0, null, ExecutionMode.LOCAL, null, null, null);
    }
}
