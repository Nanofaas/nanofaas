package it.unimib.datai.nanofaas.modules.syncqueue;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.ExecutionCompletionHandler;
import it.unimib.datai.nanofaas.controlplane.service.Metrics;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.modules.syncqueue.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueItem;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueMetrics;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueService;
import it.unimib.datai.nanofaas.workloadmetrics.FunctionCapacityRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A3: retry round trip for the sync-queue profile, using the real {@link
 * SyncQueueInvocationEnqueuer} + {@link SyncQueueService} (not a mock {@code
 * InvocationEnqueuer}) wired to a real {@link ExecutionCompletionHandler}. {@link
 * SyncQueueInvocationEnqueuer#enabled()} stays {@code false} by design (the async
 * {@code :enqueue} endpoint must keep answering 501 under this profile too), yet
 * {@link ExecutionCompletionHandler}'s retry path still calls {@code enqueue}
 * unconditionally and it must work - that is exactly what these tests pin down.
 *
 * <p>The production {@link it.unimib.datai.nanofaas.modules.syncqueue.scheduler.SyncScheduler}
 * thread is not started here; each attempt is pumped manually the same way {@code
 * SyncScheduler.tickOnceInternal} would (acquire the function's slot, pop the real
 * queue, dispatch), which keeps the test deterministic.
 */
class SyncQueueInvocationEnqueuerRetryIntegrationTest {

    private static SyncQueueService newQueueService(ExecutionStore store, FunctionCapacityRegistry capacityRegistry, int maxDepth) {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, maxDepth, Duration.ofSeconds(2), Duration.ofSeconds(30), 2, Duration.ofSeconds(30), 3);
        SyncQueueMetrics metrics = new SyncQueueMetrics(new SimpleMeterRegistry());
        SyncQueueConfigSource configSource = SyncQueueConfigSource.fixed(props.runtimeDefaults());
        return new SyncQueueService(props, store, metrics, configSource, capacityRegistry, null);
    }

    private static FunctionSpec spec(int maxRetries) {
        return new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 1, 10, maxRetries,
                null, ExecutionMode.LOCAL, null, null, null);
    }

    private static InvocationTask task(String executionId, FunctionSpec spec) {
        return new InvocationTask(executionId, spec.name(), spec, new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1, InvocationKind.SYNC);
    }

    private static DispatcherRouter dispatcherFailingThenSucceeding(int failuresBeforeSuccess, AtomicInteger attempts) {
        DispatcherRouter router = mock(DispatcherRouter.class);
        when(router.dispatchLocal(any())).thenAnswer(invocation -> {
            int attempt = attempts.incrementAndGet();
            if (attempt <= failuresBeforeSuccess) {
                return CompletableFuture.completedFuture(
                        DispatchResult.warm(InvocationResult.error("ERROR", "attempt " + attempt + " failed")));
            }
            return CompletableFuture.completedFuture(DispatchResult.warm(InvocationResult.success("ok")));
        });
        return router;
    }

    private static DispatcherRouter alwaysFailingDispatcher(AtomicInteger attempts) {
        DispatcherRouter router = mock(DispatcherRouter.class);
        when(router.dispatchLocal(any())).thenAnswer(invocation -> {
            attempts.incrementAndGet();
            return CompletableFuture.completedFuture(DispatchResult.warm(InvocationResult.error("ERROR", "always fails")));
        });
        return router;
    }

    /** Stand-in for SyncScheduler.tickOnceInternal: acquire the slot, pop the real ready queue, dispatch. */
    private static void pollAndDispatch(SyncQueueService queue, SyncQueueInvocationEnqueuer enqueuer,
                                        ExecutionCompletionHandler handler, String functionName) {
        assertThat(enqueuer.tryAcquireSlot(functionName)).isTrue();
        SyncQueueItem item = queue.pollReady(Instant.now());
        assertThat(item).isNotNull();
        handler.dispatch(item.task());
    }

    @Test
    void enabledStaysFalse_soAsyncEnqueueEndpointStaysUnavailable() {
        FunctionCapacityRegistry capacityRegistry = new FunctionCapacityRegistry();
        SyncQueueInvocationEnqueuer enqueuer = new SyncQueueInvocationEnqueuer(capacityRegistry);

        assertThat(enqueuer.enabled()).isFalse();
    }

    @Test
    void zeroRetries_immediateSuccess_singleAttempt() {
        ExecutionStore store = new ExecutionStore();
        FunctionCapacityRegistry capacityRegistry = new FunctionCapacityRegistry();
        SyncQueueService queue = newQueueService(store, capacityRegistry, 10);
        queue.registerFunction("fn", 1);
        SyncQueueInvocationEnqueuer enqueuer = new SyncQueueInvocationEnqueuer(capacityRegistry, null, ignored -> { }, queue);

        AtomicInteger attempts = new AtomicInteger();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, enqueuer, dispatcherFailingThenSucceeding(0, attempts), new Metrics(new SimpleMeterRegistry()));

        FunctionSpec spec = spec(0);
        InvocationTask task = task("exec-0-retry-success", spec);
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        store.put(record);
        assertThat(enqueuer.enqueue(task)).isTrue();

        pollAndDispatch(queue, enqueuer, handler, "fn");

        assertThat(record.completion().isDone()).isTrue();
        assertThat(record.completion().join().success()).isTrue();
        assertThat(record.state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void zeroRetries_definitiveFailure_singleAttempt() {
        ExecutionStore store = new ExecutionStore();
        FunctionCapacityRegistry capacityRegistry = new FunctionCapacityRegistry();
        SyncQueueService queue = newQueueService(store, capacityRegistry, 10);
        queue.registerFunction("fn", 1);
        SyncQueueInvocationEnqueuer enqueuer = new SyncQueueInvocationEnqueuer(capacityRegistry, null, ignored -> { }, queue);

        AtomicInteger attempts = new AtomicInteger();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, enqueuer, alwaysFailingDispatcher(attempts), new Metrics(new SimpleMeterRegistry()));

        FunctionSpec spec = spec(0);
        InvocationTask task = task("exec-0-retry-failure", spec);
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        store.put(record);
        assertThat(enqueuer.enqueue(task)).isTrue();

        pollAndDispatch(queue, enqueuer, handler, "fn");

        assertThat(record.completion().isDone()).isTrue();
        assertThat(record.completion().join().success()).isFalse();
        assertThat(record.state()).isEqualTo(ExecutionState.ERROR);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void oneRetry_errorThenSuccess() {
        ExecutionStore store = new ExecutionStore();
        FunctionCapacityRegistry capacityRegistry = new FunctionCapacityRegistry();
        SyncQueueService queue = newQueueService(store, capacityRegistry, 10);
        queue.registerFunction("fn", 1);
        SyncQueueInvocationEnqueuer enqueuer = new SyncQueueInvocationEnqueuer(capacityRegistry, null, ignored -> { }, queue);

        AtomicInteger attempts = new AtomicInteger();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, enqueuer, dispatcherFailingThenSucceeding(1, attempts), new Metrics(new SimpleMeterRegistry()));

        FunctionSpec spec = spec(1);
        InvocationTask task = task("exec-1-retry-success", spec);
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        store.put(record);
        assertThat(enqueuer.enqueue(task)).isTrue();

        pollAndDispatch(queue, enqueuer, handler, "fn"); // attempt 1 fails, retry re-queued
        assertThat(record.completion().isDone()).isFalse();
        assertThat(record.state()).isEqualTo(ExecutionState.QUEUED);

        pollAndDispatch(queue, enqueuer, handler, "fn"); // attempt 2 succeeds

        assertThat(record.completion().isDone()).isTrue();
        assertThat(record.completion().join().success()).isTrue();
        assertThat(record.state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void threeRetries_errorThenSuccess_atMostFourAttempts() {
        ExecutionStore store = new ExecutionStore();
        FunctionCapacityRegistry capacityRegistry = new FunctionCapacityRegistry();
        SyncQueueService queue = newQueueService(store, capacityRegistry, 10);
        queue.registerFunction("fn", 1);
        SyncQueueInvocationEnqueuer enqueuer = new SyncQueueInvocationEnqueuer(capacityRegistry, null, ignored -> { }, queue);

        AtomicInteger attempts = new AtomicInteger();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, enqueuer, dispatcherFailingThenSucceeding(3, attempts), new Metrics(new SimpleMeterRegistry()));

        FunctionSpec spec = spec(3);
        InvocationTask task = task("exec-3-retry-success", spec);
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        store.put(record);
        assertThat(enqueuer.enqueue(task)).isTrue();

        for (int i = 0; i < 3; i++) {
            pollAndDispatch(queue, enqueuer, handler, "fn");
            assertThat(record.completion().isDone()).isFalse();
        }
        pollAndDispatch(queue, enqueuer, handler, "fn"); // 4th attempt succeeds

        assertThat(record.completion().isDone()).isTrue();
        assertThat(record.completion().join().success()).isTrue();
        assertThat(record.state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(attempts.get()).isEqualTo(4);
    }

    @Test
    void threeRetries_definitiveFailure_neverExceedsFourAttempts() {
        ExecutionStore store = new ExecutionStore();
        FunctionCapacityRegistry capacityRegistry = new FunctionCapacityRegistry();
        SyncQueueService queue = newQueueService(store, capacityRegistry, 10);
        queue.registerFunction("fn", 1);
        SyncQueueInvocationEnqueuer enqueuer = new SyncQueueInvocationEnqueuer(capacityRegistry, null, ignored -> { }, queue);

        AtomicInteger attempts = new AtomicInteger();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, enqueuer, alwaysFailingDispatcher(attempts), new Metrics(new SimpleMeterRegistry()));

        FunctionSpec spec = spec(3);
        InvocationTask task = task("exec-3-retry-failure", spec);
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        store.put(record);
        assertThat(enqueuer.enqueue(task)).isTrue();

        for (int i = 0; i < 3; i++) {
            pollAndDispatch(queue, enqueuer, handler, "fn");
            assertThat(record.completion().isDone()).isFalse();
        }
        pollAndDispatch(queue, enqueuer, handler, "fn"); // 4th and final attempt, retries exhausted

        assertThat(record.completion().isDone()).isTrue();
        assertThat(record.completion().join().success()).isFalse();
        assertThat(record.state()).isEqualTo(ExecutionState.ERROR);
        assertThat(attempts.get()).isEqualTo(4); // 1 initial + 3 retries, never more
    }

    @Test
    void retryQueueFull_terminatesTheRequestWithoutOrphanedQueuedState() {
        ExecutionStore store = new ExecutionStore();
        FunctionCapacityRegistry capacityRegistry = new FunctionCapacityRegistry();
        // maxDepth=1: only one slot in the real sync queue.
        SyncQueueService queue = newQueueService(store, capacityRegistry, 1);
        queue.registerFunction("fn", 1);
        SyncQueueInvocationEnqueuer enqueuer = new SyncQueueInvocationEnqueuer(capacityRegistry, null, ignored -> { }, queue);

        AtomicInteger attempts = new AtomicInteger();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, enqueuer, alwaysFailingDispatcher(attempts), new Metrics(new SimpleMeterRegistry()));

        FunctionSpec spec = spec(3);
        InvocationTask task = task("exec-retry-queue-full", spec);
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        store.put(record);
        assertThat(enqueuer.enqueue(task)).isTrue(); // fills the one queue slot

        assertThat(enqueuer.tryAcquireSlot("fn")).isTrue();
        SyncQueueItem item = queue.pollReady(Instant.now()); // frees the slot, queue now empty
        assertThat(item.task()).isEqualTo(task);

        // Refill the queue's one slot with unrelated work before the retry gets a chance
        // to re-enqueue itself, so the retry's own enqueue call finds it full (DEPTH).
        InvocationTask filler = task("exec-filler", spec);
        store.put(new ExecutionRecord(filler.executionId(), filler));
        assertThat(enqueuer.enqueue(filler)).isTrue();

        handler.dispatch(item.task()); // attempt 1 fails; handleRetry's re-enqueue must be rejected

        assertThat(record.completion().isDone()).isTrue();
        assertThat(record.completion().join().success()).isFalse();
        assertThat(record.state()).isNotEqualTo(ExecutionState.QUEUED);
        assertThat(record.state()).isEqualTo(ExecutionState.ERROR);
        assertThat(attempts.get()).isEqualTo(1); // the retry never got a chance to run
    }
}
