package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class QueueManagerGaugeCleanupTest {

    private static final Set<String> CONTRACTUAL_METERS = Set.of(
            "function_queue_depth",
            "function_queue_depth_by_path",
            "function_inFlight",
            "function_effective_concurrency",
            "function_scheduler_dispatch_submit_duration"
    );

    @Test
    void remove_deregistersGaugesFromMeterRegistry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueManager queueManager = new QueueManager(registry);

        FunctionSpec spec = new FunctionSpec(
                "fn1", "image", null, Map.of(), null,
                1000, 2, 10, 3, null, ExecutionMode.DEPLOYMENT, null, null, null
        );
        queueManager.getOrCreate(spec);

        assertThat(meterNames(registry, "fn1")).containsAll(CONTRACTUAL_METERS);

        // Remove function
        queueManager.remove("fn1");

        assertThat(meterNames(registry, "fn1")).isEmpty();
    }

    @Test
    void remove_nonExistentFunction_doesNotThrow() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueManager queueManager = new QueueManager(registry);

        assertThatCode(() -> queueManager.remove("nonexistent")).doesNotThrowAnyException();
    }

    @Test
    void remove_oneFunction_doesNotAffectOther() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueManager queueManager = new QueueManager(registry);

        FunctionSpec spec1 = new FunctionSpec(
                "fn1", "image", null, Map.of(), null,
                1000, 2, 10, 3, null, ExecutionMode.DEPLOYMENT, null, null, null
        );
        FunctionSpec spec2 = new FunctionSpec(
                "fn2", "image", null, Map.of(), null,
                1000, 2, 10, 3, null, ExecutionMode.DEPLOYMENT, null, null, null
        );
        queueManager.getOrCreate(spec1);
        queueManager.getOrCreate(spec2);
        Set<Meter.Id> fn2MetersBefore = meterIds(registry, "fn2");

        // Remove only fn1
        queueManager.remove("fn1");

        assertThat(meterIds(registry, "fn2")).isEqualTo(fn2MetersBefore);
        assertThat(meterNames(registry, "fn2")).containsAll(CONTRACTUAL_METERS);

        assertThat(meterNames(registry, "fn1")).isEmpty();
    }

    private static Set<String> meterNames(SimpleMeterRegistry registry, String functionName) {
        return meterIds(registry, functionName).stream()
                .map(Meter.Id::getName)
                .collect(Collectors.toSet());
    }

    private static Set<Meter.Id> meterIds(SimpleMeterRegistry registry, String functionName) {
        return registry.getMeters().stream()
                .map(Meter::getId)
                .filter(id -> functionName.equals(id.getTag("function")))
                .collect(Collectors.toUnmodifiableSet());
    }
}
