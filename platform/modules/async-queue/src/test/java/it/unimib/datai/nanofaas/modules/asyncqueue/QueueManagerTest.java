package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.workloadmetrics.FunctionCapacityRegistry;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueManagerTest {

    @Test
    void setEffectiveConcurrency_updatesTheSharedCapacityRegistry() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        FunctionCapacityRegistry capacityRegistry = new FunctionCapacityRegistry();
        QueueManager manager = new QueueManager(
                meters,
                new it.unimib.datai.nanofaas.workloadmetrics.WorkloadDiagnostics(meters),
                capacityRegistry
        );
        manager.getOrCreate(spec("shared", 4));

        manager.setEffectiveConcurrency("shared", 2);

        assertThat(capacityRegistry.effectiveConcurrency("shared")).isEqualTo(2);
    }

    @Test
    void cleanupBoundToAcquiredStateMustNotReleaseRecreatedQueueState() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueManager manager = new QueueManager(registry);
        FunctionSpec spec = new FunctionSpec(
                "recreated",
                "image",
                null,
                Map.of(),
                null,
                1000,
                1,
                10,
                3,
                null,
                ExecutionMode.LOCAL,
                null,
                null,
                null
        );
        FunctionQueueState oldState = manager.getOrCreate(spec);
        assertThat(oldState.tryAcquireSlot()).isTrue();
        manager.remove("recreated");
        FunctionQueueState newState = manager.getOrCreate(spec);
        assertThat(newState.tryAcquireSlot()).isTrue();

        manager.releaseSlot("recreated", oldState);

        assertSoftly(softly -> {
            softly.assertThat(oldState.inFlight()).isZero();
            softly.assertThat(newState.inFlight()).isEqualTo(1);
            softly.assertThat(registry.get("function_dispatch_slot_hold_events")
                    .tag("function", "recreated")
                    .counter()
                    .count()).isZero();
            softly.assertThat(registry.get("function_dispatch_slot_hold_seconds")
                    .tag("function", "recreated")
                    .counter()
                    .count()).isZero();
        });
    }

    private static FunctionSpec spec(String name, int concurrency) {
        return new FunctionSpec(
                name, "image", null, Map.of(), null, 1000, concurrency, 10, 3,
                null, ExecutionMode.LOCAL, null, null, null);
    }

    @Test
    void enqueue_doesNotSignalWhenAllDispatchSlotsAreBusy() {
        QueueManager manager = new QueueManager(new SimpleMeterRegistry());
        FunctionSpec spec = new FunctionSpec(
                "busy",
                "image",
                null,
                Map.of(),
                null,
                1000,
                1,
                10,
                3,
                null,
                ExecutionMode.LOCAL,
                null,
                null,
                null
        );
        FunctionQueueState state = manager.getOrCreate(spec);
        AtomicInteger signals = new AtomicInteger();
        manager.setWorkSignaler(_ -> signals.incrementAndGet());
        assertThat(state.tryAcquireSlot()).isTrue();

        assertThat(manager.enqueue(new InvocationTask(
                "exec-busy",
                "busy",
                spec,
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                Instant.now(),
                1
        ,
        InvocationKind.SYNC
    ))).isTrue();

        assertThat(signals).hasValue(0);
    }

    @Test
    void issue008_queueIsBounded() {
        QueueManager manager = new QueueManager(new SimpleMeterRegistry());
        FunctionSpec spec = new FunctionSpec(
                "bounded",
                "image",
                null,
                Map.of(),
                null,
                1000,
                1,
                1,
                3,
                null,
                ExecutionMode.LOCAL,
                null,
                null,
                null
        );
        manager.getOrCreate(spec);

        InvocationTask first = new InvocationTask(
                "exec-1",
                "bounded",
                spec,
                new InvocationRequest("hello", Map.of()),
                null,
                null,
                Instant.now(),
                1
        ,
        InvocationKind.SYNC
    );
        InvocationTask second = new InvocationTask(
                "exec-2",
                "bounded",
                spec,
                new InvocationRequest("world", Map.of()),
                null,
                null,
                Instant.now(),
                1
        ,
        InvocationKind.SYNC
    );

        assertTrue(manager.enqueue(first));
        assertFalse(manager.enqueue(second));
    }

    @Test
    void getOrCreate_registersConcurrencyControllerGaugesWithFixedDefaults() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueManager manager = new QueueManager(registry);
        FunctionSpec spec = new FunctionSpec(
                "echo",
                "image",
                null,
                Map.of(),
                null,
                1000,
                4,
                10,
                3,
                null,
                ExecutionMode.DEPLOYMENT,
                null,
                null,
                null
        );

        manager.getOrCreate(spec);

        List<Meter> meters = registry.getMeters().stream()
                .filter(meter -> "echo".equals(meter.getId().getTag("function")))
                .toList();
        assertThat(meters).extracting(meter -> meter.getId().getName())
                .contains("function_queue_depth_by_path");
        assertThat(registry.get("function_target_inflight_per_pod")
                .tag("function", "echo")
                .gauge()
                .value()).isEqualTo(0.0);
        assertThat(registry.get("function_concurrency_controller_mode")
                .tags("function", "echo", "mode", ConcurrencyControlMode.FIXED.name())
                .gauge()
                .value()).isEqualTo(1.0);
    }

    @Test
    void updateConcurrencyController_updatesModeAndTargetGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueManager manager = new QueueManager(registry);
        FunctionSpec spec = new FunctionSpec(
                "echo",
                "image",
                null,
                Map.of(),
                null,
                1000,
                12,
                10,
                3,
                null,
                ExecutionMode.DEPLOYMENT,
                null,
                null,
                null
        );
        manager.getOrCreate(spec);

        manager.updateConcurrencyController("echo", ConcurrencyControlMode.STATIC_PER_POD, 3);

        assertThat(registry.get("function_target_inflight_per_pod")
                .tag("function", "echo")
                .gauge()
                .value()).isEqualTo(3.0);
        assertThat(registry.get("function_concurrency_controller_mode")
                .tags("function", "echo", "mode", ConcurrencyControlMode.STATIC_PER_POD.name())
                .gauge()
                .value()).isEqualTo(1.0);
        assertThat(registry.get("function_concurrency_controller_mode")
                .tags("function", "echo", "mode", ConcurrencyControlMode.FIXED.name())
                .gauge()
                .value()).isEqualTo(0.0);
    }

    @Test
    void remove_drainsQueuedTasks() {
        QueueManager manager = new QueueManager(new SimpleMeterRegistry());
        FunctionSpec spec = new FunctionSpec(
                "echo",
                "image",
                null,
                Map.of(),
                null,
                1000,
                4,
                10,
                3,
                null,
                ExecutionMode.DEPLOYMENT,
                null,
                null,
                null
        );
        manager.getOrCreate(spec);

        InvocationTask first = new InvocationTask(
                "exec-1",
                "echo",
                spec,
                new InvocationRequest("one", Map.of()),
                null,
                null,
                Instant.now(),
                1
        ,
        InvocationKind.SYNC
    );
        InvocationTask second = new InvocationTask(
                "exec-2",
                "echo",
                spec,
                new InvocationRequest("two", Map.of()),
                null,
                null,
                Instant.now(),
                1
        ,
        InvocationKind.SYNC
    );

        assertThat(manager.enqueue(first)).isTrue();
        assertThat(manager.enqueue(second)).isTrue();

        List<InvocationTask> drained = manager.remove("echo");

        assertThat(drained).containsExactly(first, second);
        assertThat(manager.get("echo")).isNull();
    }

    @Test
    void remove_closesDetachedQueueStateSoLateOffersAreRejected() {
        QueueManager manager = new QueueManager(new SimpleMeterRegistry());
        FunctionSpec spec = new FunctionSpec(
                "echo",
                "image",
                null,
                Map.of(),
                null,
                1000,
                4,
                10,
                3,
                null,
                ExecutionMode.DEPLOYMENT,
                null,
                null,
                null
        );
        FunctionQueueState state = manager.getOrCreate(spec);
        InvocationTask task = new InvocationTask(
                "exec-late",
                "echo",
                spec,
                new InvocationRequest("late", Map.of()),
                null,
                null,
                Instant.now(),
                1
        ,
        InvocationKind.SYNC
    );

        manager.remove("echo");

        assertThat(state.offer(task)).isFalse();
        assertThat(state.queued()).isZero();
    }
}
