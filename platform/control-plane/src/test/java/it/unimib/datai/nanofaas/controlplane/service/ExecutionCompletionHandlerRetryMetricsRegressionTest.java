package it.unimib.datai.nanofaas.controlplane.service;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.MutableClock;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the "High" priority optimization finding in
 * docs/control-plane-review-2026-09-05.md: "Keep the original admission instant across
 * retries" — {@code handleRetry} stamps the retry task with a brand-new
 * {@code Instant.now()}, and {@code completeUnderLock} then computes queue-wait and e2e purely
 * from the *current* (i.e. last-attempt) task's {@code enqueuedAt}. A request that gets retried
 * therefore reports an e2e/queue-wait latency measured only from the last retry, silently
 * understating the true end-to-end time the caller actually experienced and the true admission
 * pressure fed into SOJOURN-style metrics. This finding was not covered by the review's
 * {@code Audit.java}/{@code ProxyAudit.java} harness and is confirmed here directly against
 * {@link ExecutionCompletionHandler}.
 */
class ExecutionCompletionHandlerRetryMetricsRegressionTest {

    private final ExecutionStore executionStore = new ExecutionStore();
    private final RetryScheduler enqueuer = mock(RetryScheduler.class);
    private final DispatcherRouter dispatcherRouter = mock(DispatcherRouter.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final Metrics metrics = new Metrics(meterRegistry);
    private final ExecutionCompletionHandler completionHandler =
            new ExecutionCompletionHandler(executionStore, enqueuer, dispatcherRouter, metrics);

    @Test
    void e2eLatencyAfterARetry_shouldReflectTheOriginalAdmissionTime_notJustTheLastAttempt() {
        FunctionSpec spec = new FunctionSpec(
                "testFunc", "test-image", List.of(), Map.of(), null,
                30000, 4, 100, 3, null, ExecutionMode.LOCAL, null, null, null);

        // The caller was admitted 10 seconds before the first attempt even failed, and the retry
        // itself took another second. The end-to-end measurement must be computed from the
        // ORIGINAL admission, retries and their waits included — not from the retry's own enqueue.
        MutableClock clock = new MutableClock(1_000_000L, 0L);
        InvocationTask originalTask = new InvocationTask(
                "exec-retry-e2e", "testFunc", spec,
                new InvocationRequest("payload", null),
                null, null, clock.instant(), 1, InvocationKind.SYNC);
        ExecutionRecord executionRecord = new ExecutionRecord("exec-retry-e2e", originalTask, clock.source());
        executionStore.put(executionRecord);

        when(enqueuer.enqueue(any())).thenReturn(true);

        // The original caller waited 10 seconds before the first attempt was even dispatched.
        clock.advanceMillis(10_000);
        // Attempt 1 fails -> retried. resetForRetry stamps the retry attempt's enqueue with the
        // steered "now" (10s after admission), discarding nothing about the admission itself.
        completionHandler.completeExecution("exec-retry-e2e", InvocationResult.error("ERROR", "attempt 1 failed"));
        assertThat(executionRecord.completion().isDone()).isFalse();
        assertThat(executionRecord.task().attempt()).isEqualTo(2);

        // The retry runs for another second and succeeds.
        clock.advanceMillis(1_000);
        completionHandler.completeExecution("exec-retry-e2e", InvocationResult.success("ok"));
        assertThat(executionRecord.completion().isDone()).isTrue();

        Timer e2eTimer = metrics.e2eLatency("testFunc");
        double recordedE2eMs = e2eTimer.totalTime(TimeUnit.MILLISECONDS);

        // BUG (reproduced on the pre-fix code): the recorded e2e latency was computed from the
        // retry's own enqueuedAt, so it came back near 1s instead of the ~11s the caller waited.
        assertThat(recordedE2eMs)
                .as("end-to-end latency must be measured from the original admission time, "
                        + "including any retries and their waits, not just the last attempt")
                .isGreaterThanOrEqualTo(11_000.0);
    }
}
