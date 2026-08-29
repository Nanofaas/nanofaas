package it.unimib.datai.nanofaas.workloadmetrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkloadDiagnosticsTest {
    @Test
    void recordsGlobalAndPerFunctionDiagnosticsAndRemovesFunctionMeters() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        WorkloadDiagnostics diagnostics = new WorkloadDiagnostics(meters);
        diagnostics.registerFunction("echo");
        diagnostics.recordSchedulerVisitDuration(10);
        diagnostics.recordQueueOfferDuration("echo", 20);
        diagnostics.recordDispatchSlotBlocked("echo");

        assertThat(meters.get("scheduler_visit_duration").timer().count()).isEqualTo(1);
        assertThat(meters.get("function_queue_offer_duration").tag("function", "echo").timer().count()).isEqualTo(1);
        assertThat(meters.get("function_scheduler_slot_blocked").tag("function", "echo").counter().count()).isEqualTo(1);
        assertThat(meters.find("function_queue_poll_duration").timer()).isNotNull();

        diagnostics.removeFunction("echo");
        assertThat(meters.find("function_queue_offer_duration").tag("function", "echo").timer()).isNull();
        assertThat(meters.find("scheduler_visit_duration").timer()).isNotNull();
    }
}
