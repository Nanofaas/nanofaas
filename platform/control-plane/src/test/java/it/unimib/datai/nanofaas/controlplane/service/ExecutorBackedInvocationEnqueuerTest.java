package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Unit tests for the no-queue-module retry-scheduling enqueuer in isolation, without
 * going through {@link ExecutionCompletionHandler}. See {@link
 * InvocationServiceCoreRetryTest} for the full retry round trip.
 */
class ExecutorBackedInvocationEnqueuerTest {

    private ExecutorService executor;

    private static ExecutorBackedInvocationEnqueuer retryScheduler(java.util.function.Consumer<InvocationTask> dispatch,
                                                                   ExecutorService executor) {
        return new ExecutorBackedInvocationEnqueuer(task -> {
            try { dispatch.accept(task); }
            finally { task.dispatchLease().release(); }
        }, new it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry(), executor);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private static InvocationTask task(String executionId, int attempt) {
        FunctionSpec spec = new FunctionSpec("fn", "image", null, null, null,
                1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        return new InvocationTask(executionId, "fn", spec, new InvocationRequest("in", null),
                null, null, Instant.now(), attempt, InvocationKind.ASYNC);
    }

    @Test
    void enqueueHandsTheTaskToTheExecutorAsynchronously() {
        executor = it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerLifecycleSupport
                .newBoundedExecutor("test-retry", 1, 1, 4);
        List<String> observed = new CopyOnWriteArrayList<>();
        ExecutorBackedInvocationEnqueuer enqueuer =
                retryScheduler(t -> observed.add(t.executionId()), executor);

        boolean accepted = enqueuer.enqueue(task("exec-1", 1));

        assertThat(accepted).isTrue();
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(observed).containsExactly("exec-1"));
    }

    @Test
    void enqueueReturnsFalseWhenThePoolIsSaturated() throws InterruptedException {
        // core=1, max=1, queue capacity=1: one blocking task pins the sole worker, a
        // second fills the one queue slot, so a third submission has nowhere to go and
        // must be rejected.
        executor = it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerLifecycleSupport
                .newBoundedExecutor("test-retry-saturated", 1, 1, 1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        executor.execute(() -> {
            workerStarted.countDown();
            try {
                releaseWorker.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();
        executor.execute(() -> { }); // occupies the single queue slot

        ExecutorBackedInvocationEnqueuer enqueuer = retryScheduler(t -> { }, executor);
        boolean accepted = enqueuer.enqueue(task("exec-2", 2));

        assertThat(accepted).isFalse();
        releaseWorker.countDown();
    }

    @Test
    void enqueueReturnsFalseAfterTheExecutorHasBeenShutDown() {
        executor = it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerLifecycleSupport
                .newBoundedExecutor("test-retry-shutdown", 1, 1, 4);
        ExecutorBackedInvocationEnqueuer enqueuer = retryScheduler(t -> { }, executor);

        executor.shutdown();

        assertThat(enqueuer.enqueue(task("exec-3", 2))).isFalse();
    }

    @Test
    void enqueueReturnsFalseWhenSubmissionThrowsForAnyOtherReason() {
        // Stands in for "any other exception the concrete ExecutorService implementation
        // might throw from execute() itself" beyond RejectedExecutionException - the
        // enqueuer must not propagate any of them.
        ExecutorService throwingExecutor = new java.util.concurrent.AbstractExecutorService() {
            @Override public void execute(Runnable command) {
                throw new IllegalStateException("boom");
            }
            @Override public void shutdown() { }
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        };
        ExecutorBackedInvocationEnqueuer enqueuer = retryScheduler(t -> { }, throwingExecutor);

        assertThat(enqueuer.enqueue(task("exec-4", 2))).isFalse();
    }

    @Test
    void enqueueReturnsFalseWhenSubmissionThrowsAnError() {
        ExecutorService throwingExecutor = new java.util.concurrent.AbstractExecutorService() {
            @Override public void execute(Runnable command) {
                throw new AssertionError("executor failed");
            }
            @Override public void shutdown() { }
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        };
        ExecutorBackedInvocationEnqueuer enqueuer = retryScheduler(t -> { }, throwingExecutor);

        assertThat(enqueuer.enqueue(task("exec-error", 2))).isFalse();
    }

}
