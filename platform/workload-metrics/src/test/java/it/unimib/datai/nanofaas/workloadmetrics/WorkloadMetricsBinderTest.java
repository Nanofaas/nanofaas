package it.unimib.datai.nanofaas.workloadmetrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkloadMetricsBinderTest {
    @Test
    void registersFourLazyDuplicateSafeGaugesAndRemovesThem() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Map<String, int[]> values = new java.util.HashMap<>();
        values.put("echo", new int[]{1, 2, 3, 4});
        WorkloadMetricsSource source = new WorkloadMetricsSource() {
            public int queueDepth(String f) { return values.get(f)[0]; }
            public int inFlight(String f) { return values.get(f)[1]; }
            public int effectiveConcurrency(String f) { return values.get(f)[2]; }
            public int dispatchableBacklog(String f) { return values.get(f)[3]; }
        };
        WorkloadMetricsBinder binder = new WorkloadMetricsBinder(meters, source);

        binder.registerFunction("echo");
        binder.registerFunction("echo");
        assertThat(meters.getMeters()).hasSize(4);
        assertThat(meters.get("function_queue_depth").tag("function", "echo").gauge().value()).isEqualTo(1);
        values.get("echo")[0] = 9;
        assertThat(meters.get("function_queue_depth").tag("function", "echo").gauge().value()).isEqualTo(9);

        binder.removeFunction("echo");
        assertThat(meters.getMeters()).isEmpty();
    }
}
