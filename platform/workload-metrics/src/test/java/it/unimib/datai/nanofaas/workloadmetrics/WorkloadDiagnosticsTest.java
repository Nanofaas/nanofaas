package it.unimib.datai.nanofaas.workloadmetrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WorkloadDiagnosticsTest {
    @Test
    void recordsAllDiagnosticsWithExactNamesUnitsAndTags() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        WorkloadDiagnostics diagnostics = new WorkloadDiagnostics(meters);
        diagnostics.registerFunction("echo");
        diagnostics.registerFunction("echo");
        diagnostics.recordSchedulerVisitDuration(10);
        diagnostics.recordSchedulerIdleDuration(20);
        diagnostics.recordQueueOfferDuration("echo", 20);
        diagnostics.recordQueuePollDuration("echo", 30);
        diagnostics.recordSchedulerDispatchSubmitDuration("echo", 40);
        diagnostics.recordDispatchSlotHold("echo", 2_000_000_000L);
        diagnostics.recordDispatchSlotHold("echo", -1);
        diagnostics.recordSchedulerSlotBlocked("echo");

        assertThat(meters.get("scheduler_visit_duration").timer().count()).isEqualTo(1);
        assertThat(meters.get("scheduler_visit_duration").timer().totalTime(TimeUnit.NANOSECONDS)).isEqualTo(10);
        assertThat(meters.get("scheduler_visit_duration").timer().getId().getTags()).isEmpty();
        assertThat(meters.get("scheduler_idle_duration").timer().count()).isEqualTo(1);
        assertThat(meters.get("scheduler_idle_duration").timer().totalTime(TimeUnit.NANOSECONDS)).isEqualTo(20);
        assertThat(meters.get("scheduler_idle_duration").timer().getId().getTags()).isEmpty();

        assertThat(meters.get("function_queue_offer_duration").tag("function", "echo").timer().count()).isEqualTo(1);
        assertThat(meters.get("function_queue_poll_duration").tag("function", "echo").timer().count()).isEqualTo(1);
        assertThat(meters.get("function_scheduler_dispatch_submit_duration").tag("function", "echo").timer().count()).isEqualTo(1);
        assertThat(meters.get("function_dispatch_slot_hold_seconds").tag("function", "echo").counter().count()).isEqualTo(2.0);
        assertThat(meters.get("function_dispatch_slot_hold_seconds").tag("function", "echo").counter().getId().getBaseUnit()).isEqualTo("seconds");
        assertThat(meters.get("function_dispatch_slot_hold_events").tag("function", "echo").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("function_scheduler_slot_blocked").tag("function", "echo").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("function_queue_offer_duration").tag("function", "echo").timer().getId().getTags())
                .containsExactly(io.micrometer.core.instrument.Tag.of("function", "echo"));
        assertThat(meters.getMeters()).extracting(meter -> meter.getId().getName())
                .containsExactlyInAnyOrder(
                        "scheduler_visit_duration", "scheduler_idle_duration",
                        "function_queue_offer_duration", "function_queue_poll_duration",
                        "function_scheduler_dispatch_submit_duration",
                        "function_dispatch_slot_hold_seconds", "function_dispatch_slot_hold_events",
                        "function_scheduler_slot_blocked");

        diagnostics.removeFunction("echo");
        assertThat(meters.find("function_queue_offer_duration").tag("function", "echo").timer()).isNull();
        assertThat(meters.find("function_queue_poll_duration").tag("function", "echo").timer()).isNull();
        assertThat(meters.find("function_scheduler_dispatch_submit_duration").tag("function", "echo").timer()).isNull();
        assertThat(meters.find("function_dispatch_slot_hold_seconds").tag("function", "echo").counter()).isNull();
        assertThat(meters.find("function_dispatch_slot_hold_events").tag("function", "echo").counter()).isNull();
        assertThat(meters.find("function_scheduler_slot_blocked").tag("function", "echo").counter()).isNull();
        assertThat(meters.find("scheduler_visit_duration").timer()).isNotNull();
        assertThat(meters.find("scheduler_idle_duration").timer()).isNotNull();
    }
}
