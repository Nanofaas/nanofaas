package it.unimib.datai.nanofaas.modules.asyncqueue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacity;
import it.unimib.datai.nanofaas.controlplane.capacity.WaiterCapacity;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.dispatch.LocalDispatcher;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.input.RetainedInputEstimator;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.ExecutionCompletionHandler;
import it.unimib.datai.nanofaas.controlplane.service.InvocationExecutionFactory;
import it.unimib.datai.nanofaas.controlplane.service.InvocationResponseMapper;
import it.unimib.datai.nanofaas.controlplane.service.Metrics;
import it.unimib.datai.nanofaas.controlplane.service.ReactiveInvocationCoordinator;
import it.unimib.datai.nanofaas.controlplane.service.SyncInvocation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Async-queue profile matrix: the module serves both API doors — {@code :invoke}
 * (SYNC, waits for the result) and {@code :enqueue} (ASYNC, answers early) — through
 * the SAME per-function queue, capacity and scheduler. These contract tests exercise
 * the two doors separately and concurrently, and verify that they keep their distinct
 * HTTP contracts while sharing one queue and draining cleanly.
 *
 * <p>These are contract tests for the async-queue profile of the P00 verification
 * matrix, not reproductions of a specific R finding: they must keep passing as the
 * lifecycle tasks (P01-P09) change the surrounding code.
 */
class AsyncQueueInvokeEnqueueContractRegressionTest {

    private static FunctionSpec spec(int queueSize, int maxRetries) {
        return new FunctionSpec("fn", "image", null, Map.of(), null, 10000, 1, queueSize, maxRetries,
                null, ExecutionMode.LOCAL, null, null, null);
    }

    private static InvocationTask task(String executionId, FunctionSpec spec, InvocationKind kind) {
        return new InvocationTask(executionId, spec.name(), spec, new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1, kind);
    }

    /**
     * A router whose dispatches stay pending until the test completes them, so the test
     * can prove that a caller is (or is not) waiting on a real dispatch result.
     */
    private static final class RecordingDispatcherRouter extends DispatcherRouter {
        final List<CompletableFuture<DispatchResult>> dispatches = new ArrayList<>();

        RecordingDispatcherRouter() {
            super(new LocalDispatcher(), null);
        }

        @Override
        public CompletableFuture<DispatchResult> dispatchLocal(InvocationTask task) {
            CompletableFuture<DispatchResult> future = new CompletableFuture<>();
            dispatches.add(future);
            return future;
        }
    }

    /** Stand-in for the Scheduler loop: acquire the dispatch slot, pop the head, dispatch it. */
    private static InvocationTask pollAndDispatch(QueueManager queueManager,
                                                  ExecutionCompletionHandler handler,
                                                  String functionName) {
        assertThat(queueManager.tryAcquireSlot(functionName)).isTrue();
        FunctionQueueState state = queueManager.get(functionName);
        InvocationTask polled = state.poll();
        assertThat(polled).isNotNull();
        handler.dispatch(polled);
        return polled;
    }

    @Test
    void syncInvokeWaitsForTheQueuedDispatchToComplete() throws Exception {
        QueueManager queueManager = new QueueManager(new SimpleMeterRegistry());
        FunctionSpec spec = spec(10, 0);
        queueManager.getOrCreate(spec);
        QueueBackedEnqueuer enqueuer = new QueueBackedEnqueuer(queueManager);
        ExecutionStore store = new ExecutionStore();
        Metrics metrics = new Metrics(new SimpleMeterRegistry());
        AdmissionRuntime admission = admissionRuntime(store, metrics);
        RecordingDispatcherRouter router = new RecordingDispatcherRouter();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, enqueuer, router, metrics);
        ReactiveInvocationCoordinator coordinator = new ReactiveInvocationCoordinator(
                enqueuer, metrics, null, null, handler, new InvocationResponseMapper(), admission.waiters());
        InvocationExecutionFactory factory = admission.factory();
        InvocationExecutionFactory.ExecutionLookup lookup =
                factory.createOrReuseExecution("fn", spec, new InvocationRequest("payload", Map.of()),
                        null, null, InvocationKind.SYNC);

        CompletableFuture<SyncInvocation> invoke =
                coordinator.invoke(lookup, spec, 10_000).toFuture();

        // The :invoke contract waits: the task is queued and the caller is NOT answered
        // until the scheduler drains the queue to a real dispatch result.
        assertThat(queueManager.get("fn").queued()).isEqualTo(1);
        assertThat(invoke).isNotDone();

        pollAndDispatch(queueManager, handler, "fn");
        assertThat(router.dispatches).hasSize(1);
        assertThat(invoke).isNotDone();

        router.dispatches.get(0).complete(DispatchResult.warm(InvocationResult.success("ok")));

        assertThat(invoke.get(5, TimeUnit.SECONDS).response().status()).isEqualTo("success");
        assertThat(queueManager.get("fn").queued()).isZero();
        assertThat(queueManager.get("fn").inFlight()).isZero();
        assertThat(store.inFlightCount()).isZero();
    }

    @Test
    void asyncEnqueueAnswersEarlyAndCompletesWhenTheQueueIsDrained() {
        QueueManager queueManager = new QueueManager(new SimpleMeterRegistry());
        FunctionSpec spec = spec(10, 0);
        queueManager.getOrCreate(spec);
        QueueBackedEnqueuer enqueuer = new QueueBackedEnqueuer(queueManager);
        ExecutionStore store = new ExecutionStore();
        Metrics metrics = new Metrics(new SimpleMeterRegistry());
        RecordingDispatcherRouter router = new RecordingDispatcherRouter();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, enqueuer, router, metrics);

        InvocationTask asyncTask = task("async-1", spec, InvocationKind.ASYNC);
        ExecutionRecord record = new ExecutionRecord(asyncTask.executionId(), asyncTask);
        store.put(record);

        // The :enqueue contract answers early: admission succeeds and returns before any
        // dispatch has happened, so the completion is still pending.
        assertThat(enqueuer.enqueue(asyncTask)).isTrue();
        assertThat(record.completion()).isNotDone();

        pollAndDispatch(queueManager, handler, "fn");
        assertThat(router.dispatches).hasSize(1);
        assertThat(record.completion()).isNotDone();

        router.dispatches.get(0).complete(DispatchResult.warm(InvocationResult.success("ok")));

        assertThat(record.completion()).isDone();
        assertThat(record.completion().join().success()).isTrue();
        assertThat(queueManager.get("fn").queued()).isZero();
        assertThat(queueManager.get("fn").inFlight()).isZero();
    }

    @Test
    void concurrentSyncInvokeAndAsyncEnqueueShareOneSlotAndDrainCleanly() throws Exception {
        QueueManager queueManager = new QueueManager(new SimpleMeterRegistry());
        FunctionSpec spec = spec(10, 0);
        queueManager.getOrCreate(spec);
        QueueBackedEnqueuer enqueuer = new QueueBackedEnqueuer(queueManager);
        ExecutionStore store = new ExecutionStore();
        Metrics metrics = new Metrics(new SimpleMeterRegistry());
        AdmissionRuntime admission = admissionRuntime(store, metrics);
        RecordingDispatcherRouter router = new RecordingDispatcherRouter();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, enqueuer, router, metrics);
        ReactiveInvocationCoordinator coordinator = new ReactiveInvocationCoordinator(
                enqueuer, metrics, null, null, handler, new InvocationResponseMapper(), admission.waiters());
        InvocationExecutionFactory factory = admission.factory();

        // One SYNC :invoke and one ASYNC :enqueue land on the same function's queue.
        InvocationExecutionFactory.ExecutionLookup syncLookup =
                factory.createOrReuseExecution("fn", spec, new InvocationRequest("payload", Map.of()),
                        null, null, InvocationKind.SYNC);
        CompletableFuture<SyncInvocation> syncInvoke =
                coordinator.invoke(syncLookup, spec, 10_000).toFuture();

        InvocationTask asyncTask = task("async-2", spec, InvocationKind.ASYNC);
        ExecutionRecord asyncRecord = new ExecutionRecord(asyncTask.executionId(), asyncTask);
        store.put(asyncRecord);
        assertThat(enqueuer.enqueue(asyncTask)).isTrue();

        assertThat(queueManager.get("fn").queued()).isEqualTo(2);
        assertThat(syncInvoke).isNotDone();
        assertThat(asyncRecord.completion()).isNotDone();

        // Both doors share the single concurrency slot: while the SYNC dispatch holds it,
        // the ASYNC task waits in the queue and a second slot acquisition is refused.
        pollAndDispatch(queueManager, handler, "fn");
        assertThat(router.dispatches).hasSize(1);
        assertThat(queueManager.tryAcquireSlot("fn")).isFalse();
        assertThat(syncInvoke).isNotDone();
        assertThat(asyncRecord.completion()).isNotDone();

        // The SYNC dispatch completes first and releases the slot; only then can the
        // ASYNC task dispatch.
        router.dispatches.get(0).complete(DispatchResult.warm(InvocationResult.success("ok")));
        assertThat(syncInvoke.get(5, TimeUnit.SECONDS).response().status()).isEqualTo("success");

        pollAndDispatch(queueManager, handler, "fn");
        assertThat(router.dispatches).hasSize(2);
        router.dispatches.get(1).complete(DispatchResult.warm(InvocationResult.success("ok")));

        assertThat(asyncRecord.completion().join().success()).isTrue();
        assertThat(queueManager.get("fn").queued()).isZero();
        assertThat(queueManager.get("fn").inFlight()).isZero();
        assertThat(store.inFlightCount()).isZero();
    }

    @Test
    void syncInvokeWaitsAcrossARetryThatReEnqueuesThroughTheSameQueue() throws Exception {
        QueueManager queueManager = new QueueManager(new SimpleMeterRegistry());
        FunctionSpec spec = spec(10, 1); // One retry is allowed.
        queueManager.getOrCreate(spec);
        QueueBackedEnqueuer enqueuer = new QueueBackedEnqueuer(queueManager);
        ExecutionStore store = new ExecutionStore();
        Metrics metrics = new Metrics(new SimpleMeterRegistry());
        AdmissionRuntime admission = admissionRuntime(store, metrics);
        RecordingDispatcherRouter router = new RecordingDispatcherRouter();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, enqueuer, router, metrics);
        ReactiveInvocationCoordinator coordinator = new ReactiveInvocationCoordinator(
                enqueuer, metrics, null, null, handler, new InvocationResponseMapper(), admission.waiters());
        InvocationExecutionFactory factory = admission.factory();
        InvocationExecutionFactory.ExecutionLookup lookup =
                factory.createOrReuseExecution("fn", spec, new InvocationRequest("payload", Map.of()),
                        null, null, InvocationKind.SYNC);

        CompletableFuture<SyncInvocation> invoke =
                coordinator.invoke(lookup, spec, 10_000).toFuture();

        // First attempt fails; the retry re-enters the same queue.
        pollAndDispatch(queueManager, handler, "fn");
        assertThat(router.dispatches).hasSize(1);
        router.dispatches.get(0).complete(
                DispatchResult.warm(InvocationResult.error("ERROR", "transient")));
        assertThat(invoke).isNotDone();
        assertThat(queueManager.get("fn").queued()).isEqualTo(1);

        // Second attempt succeeds; the :invoke caller has been waiting the whole time.
        pollAndDispatch(queueManager, handler, "fn");
        assertThat(router.dispatches).hasSize(2);
        router.dispatches.get(1).complete(DispatchResult.warm(InvocationResult.success("ok")));

        assertThat(invoke.get(5, TimeUnit.SECONDS).response().status()).isEqualTo("success");
        assertThat(queueManager.get("fn").queued()).isZero();
        assertThat(queueManager.get("fn").inFlight()).isZero();
        assertThat(store.inFlightCount()).isZero();
    }

    @Test
    void queueSaturationRefusesNewAsyncWorkWhileTheQueuedSyncDispatchStillCompletes() {
        QueueManager queueManager = new QueueManager(new SimpleMeterRegistry());
        FunctionSpec spec = spec(1, 0); // A queue of a single slot.
        queueManager.getOrCreate(spec);
        QueueBackedEnqueuer enqueuer = new QueueBackedEnqueuer(queueManager);
        ExecutionStore store = new ExecutionStore();
        Metrics metrics = new Metrics(new SimpleMeterRegistry());
        RecordingDispatcherRouter router = new RecordingDispatcherRouter();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, enqueuer, router, metrics);

        // The SYNC task occupies the only queue slot before the scheduler has drained it.
        InvocationTask syncTask = task("sync-sat", spec, InvocationKind.SYNC);
        ExecutionRecord syncRecord = new ExecutionRecord(syncTask.executionId(), syncTask);
        store.put(syncRecord);
        assertThat(enqueuer.enqueue(syncTask)).isTrue();

        // Saturation is a refusal, not a silent wait: a second :enqueue on the same
        // function is rejected while the queue is full.
        InvocationTask rejectedAsync = task("async-sat", spec, InvocationKind.ASYNC);
        assertThat(enqueuer.enqueue(rejectedAsync)).isFalse();

        // The queued SYNC work is not lost: it still dispatches and completes.
        pollAndDispatch(queueManager, handler, "fn");
        assertThat(router.dispatches).hasSize(1);
        router.dispatches.get(0).complete(DispatchResult.warm(InvocationResult.success("ok")));

        assertThat(syncRecord.completion()).isDone();
        assertThat(syncRecord.completion().join().success()).isTrue();
        assertThat(queueManager.get("fn").queued()).isZero();
        assertThat(queueManager.get("fn").inFlight()).isZero();
    }

    private static AdmissionRuntime admissionRuntime(ExecutionStore store, Metrics metrics) {
        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        generations.register("fn", 10);
        InvocationCapacity capacity = new InvocationCapacity(
                generations, 100, 100, 1_000_000, 1_000_000, 16);
        InvocationExecutionFactory factory = new InvocationExecutionFactory(
                store, new IdempotencyStore(), metrics, capacity,
                new RetainedInputEstimator.Limits(32, 16_384, 65_536, 64L << 20));
        return new AdmissionRuntime(factory, new WaiterCapacity(generations, 100, 100));
    }

    private record AdmissionRuntime(
            InvocationExecutionFactory factory, WaiterCapacity waiters) { }
}
