package it.unimib.datai.nanofaas.controlplane.config;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class JfrGcMetricsTest {

    @Test
    void recordsEachVmOperationWithoutCallingItAGcCollection() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        JfrGcMetrics metrics = new JfrGcMetrics(registry);

        metrics.record("Collect for allocation", Duration.ofNanos(7));
        metrics.record("G1 wrapper", Duration.ofNanos(11));
        metrics.record("Collect for allocation", Duration.ofNanos(13));

        assertThat(registry.find("nanofaas_jfr_gc_vm_operation_count").functionCounters())
                .extracting(FunctionCounter::count)
                .containsExactlyInAnyOrder(2.0, 1.0);
        assertThat(registry.find("nanofaas_jfr_gc_vm_operation_time").functionCounters())
                .extracting(FunctionCounter::count)
                .containsExactlyInAnyOrder(20e-9, 11e-9);
    }
}
