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
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the "Alta" priority optimization finding in
 * docs/control-plane-review-2026-09-05.md: "Conservare l'istante originale di ammissione
 * attraverso i retry" — {@code handleRetry} stamps the retry task with a brand-new
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
    private final InvocationEnqueuer enqueuer = mock(InvocationEnqueuer.class);
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

        // The caller was admitted 10 seconds ago; this is the enqueue time a correct e2e
        // measurement must be computed from, retries included.
        Instant originalAdmission = Instant.now().minus(10, ChronoUnit.SECONDS);
        InvocationTask originalTask = new InvocationTask(
                "exec-retry-e2e", "testFunc", spec,
                new InvocationRequest("payload", null),
                null, null, originalAdmission, 1, InvocationKind.SYNC);
        ExecutionRecord executionRecord = new ExecutionRecord("exec-retry-e2e", originalTask);
        executionStore.put(executionRecord);

        when(enqueuer.enqueue(any())).thenReturn(true);

        // Attempt 1 fails -> retried. handleRetry stamps the retry task with Instant.now(),
        // discarding originalAdmission.
        completionHandler.completeExecution("exec-retry-e2e", InvocationResult.error("ERROR", "attempt 1 failed"));
        assertThat(executionRecord.completion().isDone()).isFalse();
        assertThat(executionRecord.task().attempt()).isEqualTo(2);

        // Attempt 2 (the retry) succeeds essentially immediately.
        completionHandler.completeExecution("exec-retry-e2e", InvocationResult.success("ok"));
        assertThat(executionRecord.completion().isDone()).isTrue();

        Timer e2eTimer = metrics.e2eLatency("testFunc");
        double recordedE2eMs = e2eTimer.totalTime(TimeUnit.MILLISECONDS);

        // BUG (still reproduces on current code): the recorded e2e latency is computed from
        // the retry's own enqueuedAt (just now), not the original admission 10s ago, so it
        // comes back near-zero instead of reflecting the ~10s the caller actually waited.
        assertThat(recordedE2eMs)
                .as("end-to-end latency must be measured from the original admission time, "
                        + "including any retries, not just the last attempt")
                .isGreaterThanOrEqualTo(9_000.0);
    }
}
