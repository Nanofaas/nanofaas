package it.unimib.datai.nanofaas.controlplane.service;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionLifecycle;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.execution.MutableClock;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadFailedException;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M1 acceptance: the timing model separates per-attempt wait, per-attempt service time and the
 * total invocation duration, all on monotonic time, with exactly one end-to-end conclusion per
 * invocation whatever the terminal policy decided.
 */
class ExecutionCompletionHandlerTimingTest {

    private ExecutionStore executionStore;
    private RetryScheduler enqueuer;
    private SimpleMeterRegistry meterRegistry;
    private Metrics metrics;
    private ExecutionCompletionHandler completionHandler;

    @BeforeEach
    void setUp() {
        executionStore = new ExecutionStore();
        // The owner is mandatory for settle(); attach a minimal one.
        new ExecutionLifecycle(executionStore, new IdempotencyStore());
        enqueuer = mock(RetryScheduler.class);
        meterRegistry = new SimpleMeterRegistry();
        metrics = new Metrics(meterRegistry);
        metrics.registerFunction("fn");
        completionHandler = new ExecutionCompletionHandler(
                executionStore, enqueuer, mock(DispatcherRouter.class), metrics);
    }

    @Test
    void perAttemptWait_serviceTime_andTotalDuration_areRecordedSeparatelyAcrossRetries() {
        when(enqueuer.enqueue(any())).thenReturn(true);
        FunctionSpec spec = spec("fn", 3);
        MutableClock clock = new MutableClock(1_000_000L, 0L);
        ExecutionRecord record = new ExecutionRecord("exec", task("exec", spec, clock), clock.source());
        executionStore.put(record);

        // Attempt 1: wait 5ms, service 10ms, fails -> retried (enqueued at 15ms).
        clock.advanceMillis(5);
        record.markRunning();
        clock.advanceMillis(10);
        completionHandler.completeExecution("exec", InvocationResult.error("E", "attempt 1"));
        assertThat(record.task().attempt()).isEqualTo(2);

        // Attempt 2: wait 8ms, service 12ms, succeeds (finished at 35ms).
        clock.advanceMillis(8);
        record.markRunning();
        clock.advanceMillis(12);
        completionHandler.completeExecution("exec", InvocationResult.success("ok"));

        // Three separate measures, one sample each:
        //  - total = 5 + 10 + 8 + 12 = 35ms, from the ORIGINAL admission;
        //  - service = 12ms, the final attempt's dispatch-to-completion;
        //  - wait = 8ms, the final attempt's enqueue-to-dispatch.
        assertThat(metrics.e2eLatency("fn").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(35.0);
        assertThat(metrics.latency("fn").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(12.0);
        assertThat(metrics.queueWait("fn").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(8.0);
        assertThat(metrics.e2eLatency("fn").count()).isEqualTo(1);
        assertThat(metrics.latency("fn").count()).isEqualTo(1);
        assertThat(metrics.queueWait("fn").count()).isEqualTo(1);
    }

    @Test
    void aTimeoutRecordsOneTotalConclusion_andNoCensoredServiceEvenOnLateCallbacks() {
        FunctionSpec spec = spec("fn", 3);
        MutableClock clock = new MutableClock(1_000_000L, 0L);
        ExecutionRecord record = new ExecutionRecord("exec", task("exec", spec, clock), clock.source());
        executionStore.put(record);

        // An execution-level deadline concludes the record 30ms after admission, before the
        // dispatch returns (a waiter's own budget would leave the record untouched).
        clock.advanceMillis(5);
        record.markRunning();
        clock.advanceMillis(25);
        record.markTimeout();

        // The real dispatch outcome arrives late — and then a duplicate. The total (admission ->
        // timeout) is real and recorded once; the service time is censored and never recorded.
        completionHandler.completeExecution("exec", InvocationResult.success("late"));
        completionHandler.completeExecution("exec", InvocationResult.success("duplicate"));

        Timer e2e = metrics.e2eLatency("fn");
        assertThat(e2e.count())
                .as("a late/duplicate callback must not double-sample the end-to-end conclusion")
                .isEqualTo(1);
        assertThat(e2e.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(30.0);
        assertThat(metrics.latency("fn").count())
                .as("a timeout-censored service time must not be recorded as a fast sample")
                .isZero();
        assertThat(metrics.queueWait("fn").count()).isZero();
    }

    @Test
    void aRetryThatCannotBeScheduled_stillRecordsTheInvocationTotal() {
        when(enqueuer.enqueue(any())).thenReturn(false); // queue full
        FunctionSpec spec = spec("fn", 3);
        MutableClock clock = new MutableClock(1_000_000L, 0L);
        ExecutionRecord record = new ExecutionRecord("exec", task("exec", spec, clock), clock.source());
        executionStore.put(record);

        clock.advanceMillis(5);
        record.markRunning();
        clock.advanceMillis(10);
        completionHandler.completeExecution("exec", InvocationResult.error("E", "boom"));

        assertThat(record.completion().isDone()).isTrue();
        // The invocation concluded in error; its total (admission -> conclusion) is still one sample.
        assertThat(metrics.e2eLatency("fn").count()).isEqualTo(1);
        assertThat(metrics.e2eLatency("fn").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(15.0);
        // The retry never dispatched, so there is no service time and no warm/cold classification.
        assertThat(metrics.latency("fn").count()).isZero();
        assertThat(metrics.queueWait("fn").count()).isZero();
        assertThat(meterRegistry.get("function_warm_start_total").tag("function", "fn").counter().count())
                .as("a retry that never dispatched is neither a warm nor a cold start")
                .isZero();
    }

    @Test
    void theOriginalAdmissionInstantSurvivesRetriesInTheRecord() {
        MutableClock clock = new MutableClock(1_000_000L, 0L);
        FunctionSpec spec = spec("fn", 3);
        var admission = clock.instant();
        ExecutionRecord record = new ExecutionRecord("exec", task("exec", spec, clock), clock.source());
        executionStore.put(record);

        clock.advanceMillis(7);
        record.markRunning();
        clock.advanceMillis(3);
        when(enqueuer.enqueue(any())).thenReturn(true);
        completionHandler.completeExecution("exec", InvocationResult.error("E", "attempt 1"));

        // The retry replaced the task (and its enqueuedAt), but not the invocation's admission.
        assertThat(record.task().attempt()).isEqualTo(2);
        assertThat(record.admittedAt()).isEqualTo(admission);
        assertThat(record.snapshot().admittedAt()).isEqualTo(admission);
    }

    @Test
    void anOffloadedSuccessRecordsItsEndToEndConclusion() {
        FunctionSpec spec = spec("fn", 0);
        MutableClock clock = new MutableClock(1_000_000L, 0L);
        ExecutionRecord record = new ExecutionRecord("exec", task("exec", spec, clock), clock.source());
        executionStore.put(record);

        clock.advanceMillis(40);
        completionHandler.completeOffloadedExecution("exec", InvocationResult.success("remote"));

        assertThat(metrics.e2eLatency("fn").count())
                .as("an offloaded invocation is admitted work and owes exactly one e2e conclusion")
                .isEqualTo(1);
        assertThat(metrics.e2eLatency("fn").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(40.0);
    }

    @Test
    void anOffloadedFailureRecordsItsEndToEndConclusion() {
        FunctionSpec spec = spec("fn", 0);
        MutableClock clock = new MutableClock(1_000_000L, 0L);
        ExecutionRecord record = new ExecutionRecord("exec", task("exec", spec, clock), clock.source());
        executionStore.put(record);

        clock.advanceMillis(25);
        completionHandler.failOffloadedExecution("exec",
                new OffloadFailedException("http://remote", true, "remote refused"));

        assertThat(metrics.e2eLatency("fn").count())
                .as("a failed offload still concluded the invocation and owes its total")
                .isEqualTo(1);
        assertThat(metrics.e2eLatency("fn").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(25.0);
    }

    @Test
    void anInvocationConcludedOutsideTheCompletionHandlerStillRecordsItsTotalOnSettle() {
        // The sync queue times out a queued item and settles the record itself; the completion
        // handler never sees that invocation. It was still admitted here and owes its one
        // end-to-end conclusion — and it is exactly the population that appears under overload,
        // so censoring it biases the sojourn the concurrency governor steers on.
        FunctionSpec spec = spec("fn", 0);
        MutableClock clock = new MutableClock(1_000_000L, 0L);
        ExecutionRecord record = new ExecutionRecord("exec", task("exec", spec, clock), clock.source());
        executionStore.put(record);

        clock.advanceMillis(60);
        record.markTimeout();
        executionStore.settle(record);

        assertThat(metrics.e2eLatency("fn").count()).isEqualTo(1);
        assertThat(metrics.e2eLatency("fn").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(60.0);
        assertThat(metrics.latency("fn").count())
                .as("nothing dispatched, so there is no service time to sample")
                .isZero();
    }

    @Test
    void aConclusionAlreadyEmittedElsewhereIsNotEmittedAgainByTheCompletionPath() {
        // The expiry listener runs on Caffeine's removal executor and can win the
        // single-conclusion guard between completeUnderLock releasing the monitor and
        // publishFinalCompletion recording. Standing in for that interleaving: the
        // guard is already consumed when the normal completion path publishes.
        FunctionSpec spec = spec("fn", 0);
        MutableClock clock = new MutableClock(1_000_000L, 0L);
        ExecutionRecord record = new ExecutionRecord("exec", task("exec", spec, clock), clock.source());
        executionStore.put(record);
        clock.advanceMillis(5);
        record.markRunning();
        clock.advanceMillis(10);

        assertThat(record.markMetricsRecorded()).isTrue();
        completionHandler.completeExecution("exec", InvocationResult.success("ok"));

        assertThat(metrics.e2eLatency("fn").count())
                .as("the invocation's end-to-end conclusion must be emitted exactly once")
                .isZero();
    }

    private static FunctionSpec spec(String name, int maxRetries) {
        return new FunctionSpec(
                name, "test-image", List.of(), Map.of(), null,
                30000, 4, 100, maxRetries, null, ExecutionMode.LOCAL, null, null, null);
    }

    private static InvocationTask task(String executionId, FunctionSpec spec, MutableClock clock) {
        return new InvocationTask(
                executionId, spec.name(), spec,
                new InvocationRequest("payload", null),
                null, null, clock.instant(), 1, InvocationKind.SYNC);
    }
}
