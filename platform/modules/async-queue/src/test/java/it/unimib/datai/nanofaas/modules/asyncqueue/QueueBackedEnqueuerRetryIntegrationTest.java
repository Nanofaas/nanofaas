package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A3: retry round trip for the async-queue profile, using the real {@link
 * QueueBackedEnqueuer} + {@link QueueManager} + {@link FunctionQueueState} (not a mock
 * {@code InvocationEnqueuer}) wired to a real {@link ExecutionCompletionHandler}. The
 * production {@link Scheduler} thread is not started here - each attempt is pumped
 * manually (acquire a slot, poll the real queue, dispatch), which keeps the test
 * deterministic while still exercising the real enqueue/queue-full behaviour that
 * backs the retry path.
 */
class QueueBackedEnqueuerRetryIntegrationTest {

    private static FunctionSpec spec(int maxRetries, int queueSize) {
        return new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 1, queueSize, maxRetries,
                null, ExecutionMode.LOCAL, null, null, null);
    }

    private static InvocationTask task(String executionId, FunctionSpec spec) {
        return new InvocationTask(executionId, spec.name(), spec, new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1, InvocationKind.ASYNC);
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

    /** Stand-in for the Scheduler loop: acquire the function's dispatch slot, pop the head of its real queue, dispatch it. */
    private static void pollAndDispatch(QueueManager queueManager, ExecutionCompletionHandler handler, String functionName) {
        var lease = queueManager.tryAcquireLease(functionName, queueManager.get(functionName));
        assertThat(lease).isNotNull();
        FunctionQueueState state = queueManager.get(functionName);
        InvocationTask polled = state.poll();
        assertThat(polled).isNotNull();
        handler.dispatch(polled.withDispatchLease(lease));
    }

    @Test
    void zeroRetries_immediateSuccess_singleAttempt() {
        QueueManager queueManager = new QueueManager(new SimpleMeterRegistry(), new FunctionCapacityRegistry());
        FunctionSpec spec = spec(0, 10);
        queueManager.getOrCreate(spec);
        QueueBackedEnqueuer enqueuer = new QueueBackedEnqueuer(queueManager);
        ExecutionStore store = new ExecutionStore();
        AtomicInteger attempts = new AtomicInteger();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, enqueuer, dispatcherFailingThenSucceeding(0, attempts), new Metrics(new SimpleMeterRegistry()));

        InvocationTask task = task("exec-0-retry-success", spec);
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        store.put(record);
        assertThat(enqueuer.enqueue(task)).isTrue();

        pollAndDispatch(queueManager, handler, "fn");

        assertThat(record.completion().isDone()).isTrue();
        assertThat(record.completion().join().success()).isTrue();
        assertThat(record.state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void zeroRetries_definitiveFailure_singleAttempt() {
        QueueManager queueManager = new QueueManager(new SimpleMeterRegistry(), new FunctionCapacityRegistry());
        FunctionSpec spec = spec(0, 10);
        queueManager.getOrCreate(spec);
        QueueBackedEnqueuer enqueuer = new QueueBackedEnqueuer(queueManager);
        ExecutionStore store = new ExecutionStore();
        AtomicInteger attempts = new AtomicInteger();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, enqueuer, alwaysFailingDispatcher(attempts), new Metrics(new SimpleMeterRegistry()));

        InvocationTask task = task("exec-0-retry-failure", spec);
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        store.put(record);
        assertThat(enqueuer.enqueue(task)).isTrue();

        pollAndDispatch(queueManager, handler, "fn");

        assertThat(record.completion().isDone()).isTrue();
        assertThat(record.completion().join().success()).isFalse();
        assertThat(record.state()).isEqualTo(ExecutionState.ERROR);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void oneRetry_errorThenSuccess() {
        QueueManager queueManager = new QueueManager(new SimpleMeterRegistry(), new FunctionCapacityRegistry());
        FunctionSpec spec = spec(1, 10);
        queueManager.getOrCreate(spec);
        QueueBackedEnqueuer enqueuer = new QueueBackedEnqueuer(queueManager);
        ExecutionStore store = new ExecutionStore();
        AtomicInteger attempts = new AtomicInteger();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, enqueuer, dispatcherFailingThenSucceeding(1, attempts), new Metrics(new SimpleMeterRegistry()));

        InvocationTask task = task("exec-1-retry-success", spec);
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        store.put(record);
        assertThat(enqueuer.enqueue(task)).isTrue();

        pollAndDispatch(queueManager, handler, "fn"); // attempt 1 fails, retry gets re-queued
        assertThat(record.completion().isDone()).isFalse();
        assertThat(record.state()).isEqualTo(ExecutionState.QUEUED);

        pollAndDispatch(queueManager, handler, "fn"); // attempt 2 succeeds

        assertThat(record.completion().isDone()).isTrue();
        assertThat(record.completion().join().success()).isTrue();
        assertThat(record.state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void threeRetries_definitiveFailure_neverExceedsFourAttempts() {
        QueueManager queueManager = new QueueManager(new SimpleMeterRegistry(), new FunctionCapacityRegistry());
        FunctionSpec spec = spec(3, 10);
        queueManager.getOrCreate(spec);
        QueueBackedEnqueuer enqueuer = new QueueBackedEnqueuer(queueManager);
        ExecutionStore store = new ExecutionStore();
        AtomicInteger attempts = new AtomicInteger();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, enqueuer, alwaysFailingDispatcher(attempts), new Metrics(new SimpleMeterRegistry()));

        InvocationTask task = task("exec-3-retry-failure", spec);
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        store.put(record);
        assertThat(enqueuer.enqueue(task)).isTrue();

        for (int i = 0; i < 3; i++) {
            pollAndDispatch(queueManager, handler, "fn");
            assertThat(record.completion().isDone()).isFalse();
        }
        pollAndDispatch(queueManager, handler, "fn"); // 4th and final attempt, retries exhausted

        assertThat(record.completion().isDone()).isTrue();
        assertThat(record.completion().join().success()).isFalse();
        assertThat(record.state()).isEqualTo(ExecutionState.ERROR);
        assertThat(attempts.get()).isEqualTo(4); // 1 initial + 3 retries, never more
    }

    @Test
    void threeRetries_errorThenSuccess_atMostFourAttempts() {
        QueueManager queueManager = new QueueManager(new SimpleMeterRegistry(), new FunctionCapacityRegistry());
        FunctionSpec spec = spec(3, 10);
        queueManager.getOrCreate(spec);
        QueueBackedEnqueuer enqueuer = new QueueBackedEnqueuer(queueManager);
        ExecutionStore store = new ExecutionStore();
        AtomicInteger attempts = new AtomicInteger();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, enqueuer, dispatcherFailingThenSucceeding(3, attempts), new Metrics(new SimpleMeterRegistry()));

        InvocationTask task = task("exec-3-retry-success", spec);
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        store.put(record);
        assertThat(enqueuer.enqueue(task)).isTrue();

        for (int i = 0; i < 3; i++) {
            pollAndDispatch(queueManager, handler, "fn");
            assertThat(record.completion().isDone()).isFalse();
        }
        pollAndDispatch(queueManager, handler, "fn"); // 4th attempt succeeds

        assertThat(record.completion().isDone()).isTrue();
        assertThat(record.completion().join().success()).isTrue();
        assertThat(record.state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(attempts.get()).isEqualTo(4);
    }

    @Test
    void retryQueueFull_terminatesTheRequestWithoutOrphanedQueuedState() {
        QueueManager queueManager = new QueueManager(new SimpleMeterRegistry(), new FunctionCapacityRegistry());
        FunctionSpec spec = spec(3, 1); // queueSize=1: only one slot in the real bounded queue
        queueManager.getOrCreate(spec);
        QueueBackedEnqueuer enqueuer = new QueueBackedEnqueuer(queueManager);
        ExecutionStore store = new ExecutionStore();
        AtomicInteger attempts = new AtomicInteger();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, enqueuer, alwaysFailingDispatcher(attempts), new Metrics(new SimpleMeterRegistry()));

        InvocationTask task = task("exec-retry-queue-full", spec);
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        store.put(record);
        assertThat(enqueuer.enqueue(task)).isTrue(); // fills the one queue slot

        var lease = queueManager.tryAcquireLease("fn", queueManager.get("fn"));
        assertThat(lease).isNotNull();
        InvocationTask polled = queueManager.get("fn").poll(); // frees the slot, queue now empty (0/1)
        assertThat(polled).isEqualTo(task);

        // Refill the queue's one slot with unrelated work before the retry attempt gets
        // a chance to re-enqueue itself, so the retry's own enqueue call finds it full.
        InvocationTask filler = task("exec-filler", spec);
        store.put(new ExecutionRecord(filler.executionId(), filler));
        assertThat(enqueuer.enqueue(filler)).isTrue();

        handler.dispatch(polled.withDispatchLease(lease)); // attempt 1 fails; handleRetry's re-enqueue must now be rejected

        assertThat(record.completion().isDone()).isTrue();
        assertThat(record.completion().join().success()).isFalse();
        assertThat(record.state()).isNotEqualTo(ExecutionState.QUEUED);
        assertThat(record.state()).isEqualTo(ExecutionState.ERROR);
        assertThat(attempts.get()).isEqualTo(1); // the retry never got a chance to run
    }
}
