package it.unimib.datai.nanofaas.workloadmetrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WorkloadDiagnosticsTest {
    /**
     * Two registered functions with a full round of recordings against one of them.
     *
     * Shared by the two tests below, which were one: what the meters look like after
     * recording, and what removing a function leaves behind. Separate tests because
     * they fail for separate reasons, and a single failure in the first half used to
     * hide every assertion in the second.
     */
    private static WorkloadDiagnostics recorded(SimpleMeterRegistry meters) {
        WorkloadDiagnostics diagnostics = new WorkloadDiagnostics(meters);
        diagnostics.registerFunction("echo");
        diagnostics.registerFunction("echo");
        diagnostics.registerFunction("other");
        diagnostics.recordSchedulerVisitDuration(10);
        diagnostics.recordSchedulerIdleDuration(20);
        diagnostics.recordQueueOfferDuration("echo", 20);
        diagnostics.recordQueuePollDuration("echo", 30);
        diagnostics.recordSchedulerDispatchSubmitDuration("echo", 40);
        diagnostics.recordDispatchSlotHold("echo", 2_000_000_000L);
        diagnostics.recordDispatchSlotHold("echo", -1);
        diagnostics.recordSchedulerSlotBlocked("echo");
        return diagnostics;
    }

    @Test
    void recordsAllDiagnosticsWithExactNamesUnitsAndTags() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        recorded(meters);

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
                        "function_scheduler_slot_blocked",
                        "function_queue_offer_duration", "function_queue_poll_duration",
                        "function_scheduler_dispatch_submit_duration",
                        "function_dispatch_slot_hold_seconds", "function_dispatch_slot_hold_events",
                        "function_scheduler_slot_blocked");
    }

    @Test
    void removingAFunctionDropsOnlyItsOwnMeters() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        WorkloadDiagnostics diagnostics = recorded(meters);

        diagnostics.removeFunction("echo");

        assertThat(meters.find("function_queue_offer_duration").tag("function", "echo").timer()).isNull();
        assertThat(meters.find("function_queue_poll_duration").tag("function", "echo").timer()).isNull();
        assertThat(meters.find("function_scheduler_dispatch_submit_duration").tag("function", "echo").timer()).isNull();
        assertThat(meters.find("function_dispatch_slot_hold_seconds").tag("function", "echo").counter()).isNull();
        assertThat(meters.find("function_dispatch_slot_hold_events").tag("function", "echo").counter()).isNull();
        assertThat(meters.find("function_scheduler_slot_blocked").tag("function", "echo").counter()).isNull();
        assertThat(meters.find("function_queue_offer_duration").tag("function", "other").timer()).isNotNull();
        assertThat(meters.find("function_queue_poll_duration").tag("function", "other").timer()).isNotNull();
        assertThat(meters.find("function_scheduler_dispatch_submit_duration").tag("function", "other").timer()).isNotNull();
        assertThat(meters.find("function_dispatch_slot_hold_seconds").tag("function", "other").counter()).isNotNull();
        assertThat(meters.find("function_dispatch_slot_hold_events").tag("function", "other").counter()).isNotNull();
        assertThat(meters.find("function_scheduler_slot_blocked").tag("function", "other").counter()).isNotNull();
        assertThat(meters.find("scheduler_visit_duration").timer()).isNotNull();
        assertThat(meters.find("scheduler_idle_duration").timer()).isNotNull();
    }

    @Test
    void concurrentRegisterAndRemoveLeavesNoFunctionMetersAfterRemoval() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        WorkloadDiagnostics diagnostics = new WorkloadDiagnostics(meters);
        ExecutorService workers = Executors.newFixedThreadPool(5);
        CyclicBarrier phase = new CyclicBarrier(5);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            for (int i = 0; i < 4; i++) {
                workers.submit(() -> runRecordingRace(diagnostics, phase, failure));
            }
            workers.submit(() -> runLifecycleRace(diagnostics, phase, failure));
            workers.shutdown();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            assertThat(failure).hasValue(null);
            diagnostics.removeFunction("echo");
            assertThat(meters.getMeters()).hasSize(2);
        } finally {
            workers.shutdownNow();
            diagnostics.removeFunction("echo");
        }
    }

    private static void runRecordingRace(WorkloadDiagnostics diagnostics, CyclicBarrier phase,
                                         AtomicReference<Throwable> failure) {
        try {
            for (int i = 0; i < 1_000; i++) {
                phase.await();
                diagnostics.recordQueueOfferDuration("echo", 1);
                diagnostics.recordQueuePollDuration("echo", 1);
                diagnostics.recordDispatchSubmitDuration("echo", 1);
                diagnostics.recordDispatchSlotHold("echo", 1);
                diagnostics.recordSchedulerSlotBlocked("echo");
                phase.await();
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }

    private static void runLifecycleRace(WorkloadDiagnostics diagnostics, CyclicBarrier phase,
                                         AtomicReference<Throwable> failure) {
        try {
            for (int i = 0; i < 1_000; i++) {
                phase.await();
                diagnostics.removeFunction("echo");
                diagnostics.registerFunction("echo");
                phase.await();
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }

}
