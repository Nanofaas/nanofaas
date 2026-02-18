package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueManagerTest {
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
        assertThat(meters).hasSize(7);
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
}
