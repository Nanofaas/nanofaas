package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Where the next attempt is published. It is prepared under the execution record's monitor and
 * published after the monitor is released, so the publication may walk into a scheduler's own
 * gate without ever creating a {@code record -> gate -> record} cycle. Republishing an attempt
 * is also not a second user-facing admission: the execution was admitted once, at invoke.
 */
class ExecutionCompletionRetryPublicationTest {

    private static final FunctionSpec SPEC = new FunctionSpec("fn", "test-image", null, null, null,
            30_000, 4, 100, 3, null, ExecutionMode.LOCAL, null, null, null);

    private ExecutionStore store;
    private Metrics metrics;

    @BeforeEach
    void setUp() {
        store = new ExecutionStore();
        metrics = mock(Metrics.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Timer timer = Timer.builder("test").register(registry);
        lenient().when(metrics.timers(anyString()))
                .thenReturn(new Metrics.FunctionTimers(timer, timer, timer, timer));
    }

    private ExecutionRecord seed(String executionId) {
        InvocationTask task = new InvocationTask(executionId, SPEC.name(), SPEC,
                new InvocationRequest("payload", null), null, null, Instant.now(), 1, InvocationKind.ASYNC);
        ExecutionRecord executionRecord = new ExecutionRecord(executionId, task);
        store.put(executionRecord);
        return executionRecord;
    }

    private ExecutionCompletionHandler handlerPublishingWith(RetryScheduler enqueuer) {
        return new ExecutionCompletionHandler(store, enqueuer, mock(DispatcherRouter.class), metrics);
    }

    @Test
    void theRecordMonitorIsFreeWhileTheNextAttemptIsPublished() throws Exception {
        ExecutionRecord executionRecord = seed("e1");
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch recordExercised = new CountDownLatch(1);
        AtomicBoolean monitorHeldDuringPublish = new AtomicBoolean(true);
        ExecutionCompletionHandler handler = handlerPublishingWith((task, due, rejected) -> {
            monitorHeldDuringPublish.set(Thread.holdsLock(executionRecord));
            publishing.countDown();
            try {
                recordExercised.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            return true;
        });

        Thread completion = new Thread(
                () -> handler.completeExecution("e1", InvocationResult.error("ERROR", "attempt 1 failed")));
        completion.start();

        assertThat(publishing.await(5, TimeUnit.SECONDS)).isTrue();
        // A second party takes the record while the publication is still in flight. It can only
        // get in because the completion released the monitor before publishing.
        synchronized (executionRecord) {
            assertThat(executionRecord.task().attempt()).isEqualTo(2);
            assertThat(executionRecord.state()).isEqualTo(ExecutionState.QUEUED);
        }
        recordExercised.countDown();
        completion.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(completion.isAlive()).isFalse();
        assertThat(monitorHeldDuringPublish).isFalse();
        assertThat(executionRecord.completion().isDone()).isFalse();
    }

    @Test
    void publishingTheNextAttemptIsNotASecondAdmission() {
        ExecutionRecord executionRecord = seed("e2");
        ExecutionCompletionHandler handler = handlerPublishingWith((task, due, rejected) -> true);

        handler.completeExecution("e2", InvocationResult.error("ERROR", "attempt 1 failed"));

        assertThat(executionRecord.task().attempt()).isEqualTo(2);
        verify(metrics, never()).admitted(anyString(), any());
        verify(metrics).enqueue("fn");
    }

    @Test
    void aFailedPublicationConcludesTheSameExecutionWithoutRefusingAnAdmission() {
        ExecutionRecord executionRecord = seed("e3");
        ExecutionCompletionHandler handler = handlerPublishingWith((task, due, rejected) -> false);

        handler.completeExecution("e3", InvocationResult.error("ERROR", "attempt 1 failed"));

        assertThat(executionRecord.state()).isEqualTo(ExecutionState.ERROR);
        assertThat(executionRecord.completion().isDone()).isTrue();
        verify(metrics).queueRejected("fn");
        verify(metrics, never()).refused(anyString(), any());
    }
    @Test
    void lateRefusalPreservesOriginalErrorAndDoesNotReenterRetryPolicy() {
        ExecutionRecord record = seed("late");
        var refusal = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        var publications = new java.util.concurrent.atomic.AtomicInteger();
        ExecutionCompletionHandler handler = handlerPublishingWith((task, due, rejected) -> {
            publications.incrementAndGet();
            refusal.set(rejected);
            assertThat(Thread.holdsLock(record)).isFalse();
            return true;
        });
        handler.completeExecution("late", InvocationResult.error("ORIGINAL", "attempt failed"));
        refusal.get().run();
        refusal.get().run();
        assertThat(record.completion().join().error().code()).isEqualTo("ORIGINAL");
        assertThat(record.task().attempt()).isEqualTo(2);
        assertThat(publications).hasValue(1);
    }

    @Test
    void refusalMayRunBeforePublicationReturnsTrue() {
        ExecutionRecord record = seed("inline");
        ExecutionCompletionHandler handler = handlerPublishingWith((task, due, rejected) -> {
            rejected.run();
            return true;
        });
        handler.completeExecution("inline", InvocationResult.error("ORIGINAL", "attempt failed"));
        assertThat(record.completion().join().error().code()).isEqualTo("ORIGINAL");
        assertThat(record.task().attempt()).isEqualTo(2);
    }

    @Test
    void throwingPublicationPreservesOriginalFailureAndRetryCount() {
        var record = seed("throw");
        var handler = handlerPublishingWith((task, due, rejected) -> {
            throw new java.util.concurrent.RejectedExecutionException("closed");
        });
        handler.completeExecution("throw", InvocationResult.error("ORIGINAL", "busy"), 1);
        assertThat(record.completion().join().error().code()).isEqualTo("ORIGINAL");
        verify(metrics).retry("fn");
        verify(metrics).error("fn");
    }

    @Test
    void configuredBackoffReachesMeteredPublication() {
        var record = seed("configured");
        var due = new java.util.concurrent.atomic.AtomicReference<Instant>();
        var before = Instant.now();
        var handler = new ExecutionCompletionHandler(store, (task, instant, rejected) -> {
            due.set(instant);
            return true;
        }, mock(DispatcherRouter.class), metrics, null, null,
                new it.unimib.datai.nanofaas.controlplane.config.RetryProperties(
                        java.time.Duration.ofSeconds(4), java.time.Duration.ofSeconds(4)));
        handler.completeExecution("configured", InvocationResult.error("ORIGINAL", "busy"), 1);
        assertThat(due.get()).isBetween(before.plusSeconds(2), Instant.now().plusSeconds(4));
        assertThat(record.task().attempt()).isEqualTo(2);
        verify(metrics).retry("fn");
        verify(metrics).enqueue("fn");
    }

}
