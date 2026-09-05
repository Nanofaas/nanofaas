package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.TimeSource;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.ExecutionCompletionHandler;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.controlplane.service.Metrics;
import it.unimib.datai.nanofaas.controlplane.service.RecordingWorkloadMetricsSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static it.unimib.datai.nanofaas.modules.concurrencycontrol.ConcurrencySpecs.sojournControl;
import static it.unimib.datai.nanofaas.modules.concurrencycontrol.ConcurrencySpecs.spec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M1 acceptance, at the controller: the SOJOURN controller reads the corrected end-to-end timer,
 * so a mix of retries and timeouts — whose totals span the whole caller wait, not just the last
 * attempt — drives the limit above what the same fast, in-promise traffic would produce.
 *
 * <p>The timers are not stubbed: they are populated by the real {@link ExecutionCompletionHandler}
 * on a steered clock, so what the controller sees is exactly what M1 corrected.</p>
 */
class SojournRetryTimeoutMixTest {

    private final RecordingMetricsSource metricsSource = new RecordingMetricsSource();
    private final FunctionRegistry registry = mock(FunctionRegistry.class);
    private final ConcurrencyControlProperties properties =
            new ConcurrencyControlProperties(5000L, 2, 64);
    private final SimpleMeterRegistry concurrencyRegistry = new SimpleMeterRegistry();
    private final ConcurrencyControlMetrics concurrencyMetrics =
            new ConcurrencyControlMetrics(concurrencyRegistry);

    @Test
    void aMixOfRetriesAndTimeouts_drivesTheSojournLimitAboveFastSuccessesAlone() {
        FunctionSpec function = spec("mix", 24, sojournControl(100, 1, 24));
        when(registry.listRegistered()).thenReturn(List.of(RegisteredFunction.nonManaged(function)));

        Metrics metrics = new Metrics(new SimpleMeterRegistry());
        ExecutionStore store = new ExecutionStore();
        InvocationEnqueuer enqueuer = mock(InvocationEnqueuer.class);
        when(enqueuer.enqueue(any())).thenReturn(true);
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, enqueuer, mock(DispatcherRouter.class), metrics);
        SteppedClock clock = new SteppedClock();

        AtomicLong governorClock = new AtomicLong(1_000);
        ConcurrencyGovernor governor = new ConcurrencyGovernor(
                registry,
                metrics,
                new ConcurrencyGovernor.ConcurrencyControllers(
                        new ConcurrencyControlCoordinator(
                                metricsSource, concurrencyMetrics, properties,
                                new StaticPerPodConcurrencyController(), new AdaptivePerPodConcurrencyController()),
                        new BudgetedConcurrencyController()),
                properties,
                null,
                metricsSource,
                metricsSource,
                concurrencyMetrics,
                () -> Instant.ofEpochMilli(governorClock.getAndAdd(5_000)));

        // Baseline: fast, in-promise traffic — 50ms of service inside a 60ms total, promise 100ms.
        governor.governLoop();
        recordFast(store, handler, clock, function, 100, 50, 10);
        metricsSource.queueDepth = 80;
        governor.governLoop();
        int withinPromise = metricsSource.effectiveConcurrency.get("mix");

        // The mix: retries whose total spans both attempts and their waits, and timeouts whose
        // total is the whole caller budget. Service time is still ~50ms — the pressure shows up in
        // the end-to-end total alone, which is exactly the timer SOJOURN reads.
        recordRetried(store, handler, clock, function, 40, 50, 30);
        recordTimeout(store, handler, clock, function, 20, 2_500);
        governor.governLoop();

        assertMode("mix", ConcurrencyControlMode.SOJOURN);
        assertThat(metricsSource.effectiveConcurrency.get("mix"))
                .as("retries and timeouts inflate the end-to-end total, so the controller must "
                        + "drain the backlog harder than under fast successes alone")
                .isGreaterThan(withinPromise);
    }

    private void recordFast(ExecutionStore store, ExecutionCompletionHandler handler,
                            SteppedClock clock, FunctionSpec fn, int count, long serviceMs, long waitMs) {
        for (int i = 0; i < count; i++) {
            ExecutionRecord record = newRecord(store, fn, clock);
            clock.advanceMillis(waitMs);
            record.markRunning();
            clock.advanceMillis(serviceMs);
            handler.completeExecution(record.executionId(), InvocationResult.success("ok"));
        }
    }

    private void recordRetried(ExecutionStore store, ExecutionCompletionHandler handler,
                               SteppedClock clock, FunctionSpec fn, int count, long serviceMs, long waitMs) {
        for (int i = 0; i < count; i++) {
            ExecutionRecord record = newRecord(store, fn, clock);
            clock.advanceMillis(waitMs);
            record.markRunning();
            clock.advanceMillis(serviceMs);
            handler.completeExecution(record.executionId(), InvocationResult.error("E", "attempt 1"));
            clock.advanceMillis(waitMs);
            record.markRunning();
            clock.advanceMillis(serviceMs);
            handler.completeExecution(record.executionId(), InvocationResult.success("ok"));
        }
    }

    private void recordTimeout(ExecutionStore store, ExecutionCompletionHandler handler,
                               SteppedClock clock, FunctionSpec fn, int count, long totalMs) {
        for (int i = 0; i < count; i++) {
            ExecutionRecord record = newRecord(store, fn, clock);
            record.markRunning();
            clock.advanceMillis(totalMs);
            record.markTimeout();
            // The real dispatch shows up late; the total is recorded once, the service never.
            handler.completeExecution(record.executionId(), InvocationResult.success("late"));
        }
    }

    private static final AtomicLong executionIds = new AtomicLong();

    private static ExecutionRecord newRecord(ExecutionStore store, FunctionSpec fn, SteppedClock clock) {
        String id = "exec-" + executionIds.incrementAndGet();
        InvocationTask task = new InvocationTask(
                id, fn.name(), fn,
                new InvocationRequest("payload", null),
                null, null, clock.instant(), 1, InvocationKind.SYNC);
        ExecutionRecord record = new ExecutionRecord(id, task, clock.source());
        store.put(record);
        return record;
    }

    private void assertMode(String functionName, ConcurrencyControlMode mode) {
        assertThat(concurrencyRegistry.get("function_concurrency_controller_mode")
                .tags("function", functionName, "mode", mode.name()).gauge().value()).isEqualTo(1.0);
    }

    /** A monotonic clock and its wall-clock counterpart, advanced together without sleeping. */
    private static final class SteppedClock {
        private final AtomicLong nanos = new AtomicLong(0);

        TimeSource source() {
            return new TimeSource(() -> Instant.ofEpochMilli(nanos.get() / 1_000_000), nanos::get);
        }

        void advanceMillis(long millis) {
            nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
        }

        Instant instant() {
            return Instant.ofEpochMilli(nanos.get() / 1_000_000);
        }
    }

    private static final class RecordingMetricsSource implements RecordingWorkloadMetricsSource {
        private final Map<String, Integer> effectiveConcurrency = new HashMap<>();
        private int queueDepth;

        @Override
        public int queueDepth(String functionName) {
            return queueDepth;
        }

        @Override
        public int inFlight(String functionName) {
            return 0;
        }

        @Override
        public void setEffectiveConcurrency(String functionName, int value) {
            effectiveConcurrency.put(functionName, value);
        }
    }
}
