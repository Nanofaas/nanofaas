package it.unimib.datai.nanofaas.modules.asyncqueue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AsyncQueueDiagnosticsTest {

    @Test
    void schedulerPublishesReleaseToReacquisitionDelay() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueManager queueManager = new QueueManager(registry);
        InvocationService invocationService = mock(InvocationService.class);
        FunctionSpec spec = new FunctionSpec(
                "echo", "image", null, Map.of(), null,
                1000, 1, 10, 3, null, ExecutionMode.LOCAL, null, null, null
        );
        queueManager.getOrCreate(spec);
        assertThat(queueManager.enqueue(task("first", spec))).isTrue();
        assertThat(queueManager.enqueue(task("second", spec))).isTrue();

        Scheduler scheduler = new Scheduler(queueManager, invocationService);
        scheduler.init();
        scheduler.start();
        try {
            scheduler.signalWork("echo");
            Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    verify(invocationService, times(1)).dispatch(org.mockito.ArgumentMatchers.any())
            );

            queueManager.releaseSlot("echo");

            Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    verify(invocationService, times(2)).dispatch(org.mockito.ArgumentMatchers.any())
            );
        } finally {
            scheduler.stop();
        }

        assertThat(registry.get("function_dispatch_slot_reacquisition_delay").tag("function", "echo")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get("function_dispatch_slot_reacquisition_active_delay").tag("function", "echo")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get("function_scheduler_slot_blocked").tag("function", "echo")
                .counter().count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void releasePublishesDispatchSlotHoldDuration() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueManager queueManager = new QueueManager(registry);
        FunctionSpec spec = new FunctionSpec(
                "echo", "image", null, Map.of(), null,
                1000, 10, 2, 3, null, ExecutionMode.LOCAL, null, null, null
        );
        queueManager.getOrCreate(spec);

        assertThat(queueManager.tryAcquireSlot("echo")).isTrue();
        queueManager.releaseSlot("echo");
        queueManager.releaseSlot("echo");

        assertThat(registry.get("function_dispatch_slot_hold_duration").tag("function", "echo")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get("function_dispatch_slot_hold_duration").tag("function", "echo")
                .timer().totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isPositive();
    }

    @Test
    void schedulerPublishesQueueAndWakeupDiagnosticsWithoutChangingDispatch() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueManager queueManager = new QueueManager(registry);
        InvocationService invocationService = mock(InvocationService.class);
        FunctionSpec spec = new FunctionSpec(
                "echo", "image", null, Map.of(), null,
                1000, 10, 10, 3, null, ExecutionMode.LOCAL, null, null, null
        );
        queueManager.getOrCreate(spec);

        InvocationTask first = task("first", spec);
        InvocationTask second = task("second", spec);
        InvocationTask third = task("third", spec);
        assertThat(queueManager.enqueue(first)).isTrue();
        assertThat(queueManager.enqueue(second)).isTrue();
        assertThat(queueManager.enqueue(third)).isTrue();
        assertThat(registry.get("function_dispatchable_backlog")
                .tag("function", "echo").gauge().value()).isEqualTo(3.0);

        Scheduler scheduler = new Scheduler(queueManager, invocationService);
        scheduler.init();
        scheduler.start();
        try {
            scheduler.signalWork("echo");

            Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    verify(invocationService, times(3)).dispatch(org.mockito.ArgumentMatchers.any())
            );
        } finally {
            scheduler.stop();
        }

        assertThat(registry.get("function_queue_offer_duration").tag("function", "echo")
                .timer().count()).isEqualTo(3);
        assertThat(registry.get("function_queue_poll_duration").tag("function", "echo")
                .timer().count()).isEqualTo(4);
        assertThat(registry.get("function_scheduler_wakeup_delay").tag("function", "echo")
                .timer().count()).isGreaterThanOrEqualTo(1);
        assertThat(registry.get("function_scheduler_batch_limit").tag("function", "echo")
                .counter().count()).isGreaterThanOrEqualTo(1);
        assertThat(registry.get("function_scheduler_dispatch_submit_duration").tag("function", "echo")
                .timer().count()).isEqualTo(3);
    }

    @Test
    void schedulerCountsCoalescedSignals() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueManager queueManager = new QueueManager(registry);
        FunctionSpec spec = new FunctionSpec(
                "echo", "image", null, Map.of(), null,
                1000, 1, 10, 3, null, ExecutionMode.LOCAL, null, null, null
        );
        queueManager.getOrCreate(spec);

        Scheduler scheduler = new Scheduler(queueManager, mock(InvocationService.class));
        scheduler.init();
        scheduler.signalWork("echo");
        scheduler.signalWork("echo");

        assertThat(registry.get("function_scheduler_signal_coalesced").tag("function", "echo")
                .counter().count()).isEqualTo(1);
    }

    private static InvocationTask task(String executionId, FunctionSpec spec) {
        return new InvocationTask(
                executionId,
                spec.name(),
                spec,
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                Instant.now(),
                1
        );
    }
}
