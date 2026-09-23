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
        ExecutionCompletionHandler handler = handlerPublishingWith(task -> {
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
        ExecutionCompletionHandler handler = handlerPublishingWith(task -> true);

        handler.completeExecution("e2", InvocationResult.error("ERROR", "attempt 1 failed"));

        assertThat(executionRecord.task().attempt()).isEqualTo(2);
        verify(metrics, never()).admitted(anyString(), any());
        verify(metrics).enqueue("fn");
    }

    @Test
    void aFailedPublicationConcludesTheSameExecutionWithoutRefusingAnAdmission() {
        ExecutionRecord executionRecord = seed("e3");
        ExecutionCompletionHandler handler = handlerPublishingWith(task -> false);

        handler.completeExecution("e3", InvocationResult.error("ERROR", "attempt 1 failed"));

        assertThat(executionRecord.state()).isEqualTo(ExecutionState.ERROR);
        assertThat(executionRecord.completion().isDone()).isTrue();
        verify(metrics).queueRejected("fn");
        verify(metrics, never()).refused(anyString(), any());
    }
}
