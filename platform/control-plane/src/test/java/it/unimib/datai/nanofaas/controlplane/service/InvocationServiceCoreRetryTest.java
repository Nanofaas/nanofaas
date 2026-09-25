package it.unimib.datai.nanofaas.controlplane.service;

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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A3: retry round trip for the profile with no queue module loaded at all - the
 * profile configured by {@link ServiceDefaultsConfiguration} serves by default.
 * Uses the real {@link ExecutorBackedInvocationEnqueuer} (not a mock/no-op) wired to a
 * real {@link ExecutionCompletionHandler}, with {@link DispatcherRouter} mocked to
 * control attempt outcomes. Retry now happens off the calling thread, so assertions
 * poll with Awaitility instead of asserting immediately after {@code dispatch}.
 */
class InvocationServiceCoreRetryTest {

    private ExecutorService retryExecutor;
    private ExecutionStore store;

    @AfterEach
    void tearDown() {
        if (retryExecutor != null) {
            retryExecutor.shutdownNow();
        }
    }

    /**
     * ExecutionCompletionHandler and ExecutorBackedInvocationEnqueuer are mutually
     * referential in production (via an ObjectProvider, see ServiceDefaultsConfiguration);
     * in a plain unit test there is no Spring container to worry about circular eager
     * creation for, so the handler reference is simply patched in right after construction.
     */
    private ExecutionCompletionHandler newHandlerWithRealRetryEnqueuer(DispatcherRouter dispatcherRouter) {
        store = new ExecutionStore();
        retryExecutor = it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerLifecycleSupport
                .newBoundedExecutor("test-core-retry", 2, 4, 32);
        return handlerBackedBy(store, dispatcherRouter, retryExecutor);
    }

    private static ExecutionCompletionHandler handlerBackedBy(ExecutionStore store,
                                                               DispatcherRouter dispatcherRouter,
                                                               ExecutorService executor) {
        ExecutionCompletionHandler[] handlerHolder = new ExecutionCompletionHandler[1];
        ExecutorBackedInvocationEnqueuer enqueuer =
                new ExecutorBackedInvocationEnqueuer(t -> handlerHolder[0].dispatch(t), new it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry(), executor); // NOSONAR (java:S1612): handlerHolder[0]::dispatch would capture null
        ExecutionCompletionHandler handler =
                new ExecutionCompletionHandler(store, enqueuer, dispatcherRouter, new Metrics(new SimpleMeterRegistry()));
        handlerHolder[0] = handler;
        return handler;
    }

    private static FunctionSpec spec(int maxRetries) {
        return new FunctionSpec("fn", "test-image", null, null, null,
                30_000, 4, 100, maxRetries, null, ExecutionMode.LOCAL, null, null, null);
    }

    private static InvocationTask task(String executionId, FunctionSpec spec) {
        return new InvocationTask(executionId, spec.name(), spec, new InvocationRequest("payload", null),
                null, null, Instant.now(), 1, InvocationKind.ASYNC);
    }

    private ExecutionRecord seedRecord(FunctionSpec spec, String executionId) {
        InvocationTask task = task(executionId, spec);
        ExecutionRecord executionRecord = new ExecutionRecord(task.executionId(), task);
        store.put(executionRecord);
        return executionRecord;
    }

    /** A dispatcher whose dispatchLocal fails {@code failuresBeforeSuccess} times, then succeeds. */
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
            return CompletableFuture.completedFuture(
                    DispatchResult.warm(InvocationResult.error("ERROR", "always fails")));
        });
        return router;
    }

    @Test
    void zeroRetries_immediateSuccess_singleAttempt() {
        AtomicInteger attempts = new AtomicInteger();
        ExecutionCompletionHandler handler = newHandlerWithRealRetryEnqueuer(dispatcherFailingThenSucceeding(0, attempts));
        FunctionSpec spec = spec(0);
        ExecutionRecord executionRecord = seedRecord(spec, "exec-0-retry-success");

        handler.dispatch(executionRecord.task());

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(executionRecord.completion().isDone()).isTrue());
        assertThat(executionRecord.completion().join().success()).isTrue();
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void zeroRetries_definitiveFailure_singleAttempt() {
        AtomicInteger attempts = new AtomicInteger();
        ExecutionCompletionHandler handler = newHandlerWithRealRetryEnqueuer(alwaysFailingDispatcher(attempts));
        FunctionSpec spec = spec(0);
        ExecutionRecord executionRecord = seedRecord(spec, "exec-0-retry-failure");

        handler.dispatch(executionRecord.task());

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(executionRecord.completion().isDone()).isTrue());
        assertThat(executionRecord.completion().join().success()).isFalse();
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.ERROR);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void oneRetry_errorThenSuccess() {
        AtomicInteger attempts = new AtomicInteger();
        ExecutionCompletionHandler handler = newHandlerWithRealRetryEnqueuer(dispatcherFailingThenSucceeding(1, attempts));
        FunctionSpec spec = spec(1);
        ExecutionRecord executionRecord = seedRecord(spec, "exec-1-retry-success");

        handler.dispatch(executionRecord.task());

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(executionRecord.completion().isDone()).isTrue());
        assertThat(executionRecord.completion().join().success()).isTrue();
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(attempts.get()).isEqualTo(2); // 1 initial + 1 retry
    }

    @Test
    void oneRetry_definitiveFailureAfterExhaustion() {
        AtomicInteger attempts = new AtomicInteger();
        ExecutionCompletionHandler handler = newHandlerWithRealRetryEnqueuer(alwaysFailingDispatcher(attempts));
        FunctionSpec spec = spec(1);
        ExecutionRecord executionRecord = seedRecord(spec, "exec-1-retry-failure");

        handler.dispatch(executionRecord.task());

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(executionRecord.completion().isDone()).isTrue());
        assertThat(executionRecord.completion().join().success()).isFalse();
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.ERROR);
        assertThat(attempts.get()).isEqualTo(2); // 1 initial + 1 retry, then exhausted
    }

    @Test
    void threeRetries_errorThenSuccess_atMostFourAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        ExecutionCompletionHandler handler = newHandlerWithRealRetryEnqueuer(dispatcherFailingThenSucceeding(3, attempts));
        FunctionSpec spec = spec(3);
        ExecutionRecord executionRecord = seedRecord(spec, "exec-3-retry-success");

        handler.dispatch(executionRecord.task());

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(executionRecord.completion().isDone()).isTrue());
        assertThat(executionRecord.completion().join().success()).isTrue();
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(attempts.get()).isEqualTo(4); // 1 initial + 3 retries
    }

    @Test
    void threeRetries_definitiveFailure_neverExceedsFourAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        ExecutionCompletionHandler handler = newHandlerWithRealRetryEnqueuer(alwaysFailingDispatcher(attempts));
        FunctionSpec spec = spec(3);
        ExecutionRecord executionRecord = seedRecord(spec, "exec-3-retry-failure");

        handler.dispatch(executionRecord.task());

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(executionRecord.completion().isDone()).isTrue());
        assertThat(executionRecord.completion().join().success()).isFalse();
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.ERROR);
        // Give any wrongly-scheduled extra attempt a chance to show up before asserting the bound.
        await().pollDelay(200, TimeUnit.MILLISECONDS).atMost(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(attempts.get()).isEqualTo(4)); // 1 initial + 3 retries, never more
    }

    @Test
    void executorShutdown_terminatesTheRequestWithoutOrphanedQueuedState() {
        AtomicInteger attempts = new AtomicInteger();
        ExecutionCompletionHandler handler = newHandlerWithRealRetryEnqueuer(alwaysFailingDispatcher(attempts));
        FunctionSpec spec = spec(3);
        ExecutionRecord executionRecord = seedRecord(spec, "exec-executor-shutdown");

        retryExecutor.shutdown(); // simulate the pool being unavailable before the first failure

        handler.dispatch(executionRecord.task());

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(executionRecord.completion().isDone()).isTrue());
        assertThat(executionRecord.completion().join().success()).isFalse();
        assertThat(executionRecord.state()).isNotEqualTo(ExecutionState.QUEUED);
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.ERROR);
        assertThat(attempts.get()).isEqualTo(1); // only the initial attempt ran; the retry never got scheduled
    }

    @Test
    void poolSaturation_terminatesTheRequestWithoutOrphanedQueuedState() throws InterruptedException {
        store = new ExecutionStore();
        // core=1, max=1, queue capacity=1: pin the worker and fill the queue before the
        // execution's own retry attempt is scheduled, forcing enqueue() to reject it.
        retryExecutor = it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerLifecycleSupport
                .newBoundedExecutor("test-core-retry-saturated", 1, 1, 1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        retryExecutor.execute(() -> {
            workerStarted.countDown();
            try {
                releaseWorker.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();
        retryExecutor.execute(() -> { }); // occupies the single queue slot

        AtomicInteger attempts = new AtomicInteger();
        DispatcherRouter router = alwaysFailingDispatcher(attempts);
        ExecutionCompletionHandler handler = handlerBackedBy(store, router, retryExecutor);

        FunctionSpec spec = spec(3);
        ExecutionRecord executionRecord = seedRecord(spec, "exec-pool-saturated");

        handler.dispatch(executionRecord.task());

        assertThat(executionRecord.completion().isDone()).isTrue();
        assertThat(executionRecord.completion().join().success()).isFalse();
        assertThat(executionRecord.state()).isNotEqualTo(ExecutionState.QUEUED);
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.ERROR);
        assertThat(attempts.get()).isEqualTo(1);

        releaseWorker.countDown();
    }

    @Test
    void manyImmediateRetries_doNotOverflowTheStack() {
        // Regression for "avoid recursion when the future completes immediately": LOCAL-style
        // dispatch resolves synchronously, so without the executor hop, handleRetry -> enqueue
        // -> dispatch -> handleRetry would recurse directly on the calling thread.
        int maxRetries = 20_000;
        AtomicInteger attempts = new AtomicInteger();
        ExecutionCompletionHandler handler = newHandlerWithRealRetryEnqueuer(alwaysFailingDispatcher(attempts));
        FunctionSpec spec = spec(maxRetries);
        ExecutionRecord executionRecord = seedRecord(spec, "exec-many-retries");

        handler.dispatch(executionRecord.task());

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(executionRecord.completion().isDone()).isTrue());
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.ERROR);
        assertThat(attempts.get()).isEqualTo(maxRetries + 1);
    }
}
