package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * The queue's own diagnostics, driven through {@link QueueManager} - not through a scheduler.
 *
 * <p>Task 13b (issue #208) removed four tests from this file
 * ({@code schedulerPublishesQueueAndWakeupDiagnosticsWithoutChangingDispatch},
 * {@code schedulerCountsCoalescedSignals}, {@code schedulerVisitIncludesActivationBookkeeping},
 * {@code schedulerThreadTimeNeverExceedsTheElapsedWallClock}). Each drove the retired
 * {@code modules.asyncqueue.Scheduler} loop and asserted the meters <em>that loop</em> recorded -
 * {@code function_scheduler_wakeup_delay}, {@code _poll_delay},
 * {@code _activation_bookkeeping_duration}, {@code _signal_enqueue_duration},
 * {@code _signal_coalesced}, {@code _batch_limit} and the untagged
 * {@code scheduler_visit_duration}/{@code scheduler_idle_duration}. With the loop deleted nothing
 * records them: the composed engine records its own reservation-based set instead (see
 * {@code EngineWorkloadMetricsSource} and {@code EngineWorkloadMetricsTest}). What survives here
 * is what a surviving producer still records, and {@code WorkloadDiagnostics}' own recorders -
 * including those same names - remain covered at the recorder level by
 * {@code WorkloadDiagnosticsTest}.
 */
class AsyncQueueDiagnosticsTest {

    @Test
    void slotHoldMetricPublishesAggregatesWithoutARequestDistribution() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueManager queueManager = new QueueManager(registry, new FunctionCapacityRegistry());
        FunctionSpec spec = new FunctionSpec(
                "echo", "image", null, Map.of(), null,
                1000, 10, 2, 3, null, ExecutionMode.LOCAL, null, null, null
        );
        queueManager.getOrCreate(spec);

        assertSoftly(softly -> {
            softly.assertThat(registry.find("function_dispatch_slot_hold_duration").timer())
                    .isNull();
            softly.assertThat(registry.find("function_dispatch_slot_hold_seconds").counter())
                    .isNotNull();
            softly.assertThat(registry.find("function_dispatch_slot_hold_events").counter())
                    .isNotNull();
        });
    }

    @Test
    void releasePublishesDispatchSlotHoldDuration() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueManager queueManager = new QueueManager(registry, new FunctionCapacityRegistry());
        FunctionSpec spec = new FunctionSpec(
                "echo", "image", null, Map.of(), null,
                1000, 10, 2, 3, null, ExecutionMode.LOCAL, null, null, null
        );
        queueManager.getOrCreate(spec);

        var lease = queueManager.tryAcquireLease("echo", queueManager.get("echo"));
        assertThat(lease).isNotNull();
        lease.release();
        lease.release();

        assertThat(registry.get("function_dispatch_slot_hold_events").tag("function", "echo")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("function_dispatch_slot_hold_seconds").tag("function", "echo")
                .counter().count()).isPositive();
    }
}
