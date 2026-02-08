package it.unimib.datai.nanofaas.controlplane.queue;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QueueManagerGaugeCleanupTest {

    @Test
    void remove_deregistersGaugesFromMeterRegistry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueManager queueManager = new QueueManager(registry);

        FunctionSpec spec = new FunctionSpec(
                "fn1", "image", null, Map.of(), null,
                1000, 2, 10, 3, null, ExecutionMode.REMOTE, null, null, null
        );
        queueManager.getOrCreate(spec);

        // Verify gauges were registered
        List<Meter> gaugesBefore = registry.getMeters().stream()
                .filter(m -> m.getId().getTag("function") != null
                        && m.getId().getTag("function").equals("fn1"))
                .toList();
        assertThat(gaugesBefore).hasSize(2);

        // Remove function
        queueManager.remove("fn1");

        // Verify gauges are deregistered
        List<Meter> gaugesAfter = registry.getMeters().stream()
                .filter(m -> m.getId().getTag("function") != null
                        && m.getId().getTag("function").equals("fn1"))
                .toList();
        assertThat(gaugesAfter).isEmpty();
    }

    @Test
    void remove_nonExistentFunction_doesNotThrow() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueManager queueManager = new QueueManager(registry);

        // Should not throw
        queueManager.remove("nonexistent");
    }

    @Test
    void remove_oneFunction_doesNotAffectOther() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueManager queueManager = new QueueManager(registry);

        FunctionSpec spec1 = new FunctionSpec(
                "fn1", "image", null, Map.of(), null,
                1000, 2, 10, 3, null, ExecutionMode.REMOTE, null, null, null
        );
        FunctionSpec spec2 = new FunctionSpec(
                "fn2", "image", null, Map.of(), null,
                1000, 2, 10, 3, null, ExecutionMode.REMOTE, null, null, null
        );
        queueManager.getOrCreate(spec1);
        queueManager.getOrCreate(spec2);

        // Remove only fn1
        queueManager.remove("fn1");

        // fn2 gauges should still exist
        List<Meter> fn2Gauges = registry.getMeters().stream()
                .filter(m -> m.getId().getTag("function") != null
                        && m.getId().getTag("function").equals("fn2"))
                .toList();
        assertThat(fn2Gauges).hasSize(2);

        // fn1 gauges should be gone
        List<Meter> fn1Gauges = registry.getMeters().stream()
                .filter(m -> m.getId().getTag("function") != null
                        && m.getId().getTag("function").equals("fn1"))
                .toList();
        assertThat(fn1Gauges).isEmpty();
    }
}
