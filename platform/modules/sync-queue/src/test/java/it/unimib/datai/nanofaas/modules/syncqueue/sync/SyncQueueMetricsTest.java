package it.unimib.datai.nanofaas.modules.syncqueue.sync;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

class SyncQueueMetricsTest {
    @Test
    void removedNamesDoNotAccumulateInMetricLifecycleState() throws Exception {
        SyncQueueMetrics metrics = new SyncQueueMetrics(new SimpleMeterRegistry(), () -> 0, name -> 0);
        for (int i = 0; i < 1000; i++) {
            String function = "removed-" + i;
            metrics.registerFunction(function);
            metrics.removeFunctionState(function);
        }

        Field field = SyncQueueMetrics.class.getDeclaredField("registeredFunctions");
        field.setAccessible(true);
        assertThat((Collection<?>) field.get(metrics)).isEmpty();
    }

    @Test
    void prometheusRegistryKeepsGlobalAndPerFunctionMeters() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        SyncQueueMetrics metrics = new SyncQueueMetrics(registry, () -> 0, name -> 0);
        metrics.registerFunction("echo");
        metrics.recordWait("echo", 10);

        assertThat(registry.find("sync_queue_depth").tag("function", "").gauge()).isNotNull();
        assertThat(registry.find("sync_queue_depth").tag("function", "echo").gauge()).isNotNull();
        assertThat(registry.find("sync_queue_wait_seconds").tag("function", "").timer()).isNotNull();
        assertThat(registry.find("sync_queue_wait_seconds").tag("function", "echo").timer()).isNotNull();
    }

    @Test
    void removeFunctionState_removesPerFunctionMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SyncQueueMetrics metrics = new SyncQueueMetrics(registry, () -> 0, name -> 0);
        metrics.registerFunction("echo");

        metrics.admitted("echo");
        metrics.rejected("echo");
        metrics.timedOut("echo");
        metrics.recordWait("echo", 10);

        assertThat(registry.find("sync_queue_depth").tag("function", "echo").gauge()).isNotNull();
        assertThat(registry.find("sync_queue_admitted_total").tag("function", "echo").counter()).isNotNull();
        assertThat(registry.find("sync_queue_rejected_total").tag("function", "echo").counter()).isNotNull();
        assertThat(registry.find("sync_queue_timedout_total").tag("function", "echo").counter()).isNotNull();
        assertThat(registry.find("sync_queue_wait_seconds").tag("function", "echo").timer()).isNotNull();

        metrics.removeFunctionState("echo");

        assertThat(registry.find("sync_queue_depth").tag("function", "echo").gauge()).isNull();
        assertThat(registry.find("sync_queue_admitted_total").tag("function", "echo").counter()).isNull();
        assertThat(registry.find("sync_queue_rejected_total").tag("function", "echo").counter()).isNull();
        assertThat(registry.find("sync_queue_timedout_total").tag("function", "echo").counter()).isNull();
        assertThat(registry.find("sync_queue_wait_seconds").tag("function", "echo").timer()).isNull();
        assertThat(registry.find("sync_queue_depth").gauge()).isNotNull();
        assertThat(registry.find("sync_queue_wait_seconds").timer()).isNotNull();
    }

    @Test
    void removedFunction_doesNotRecreateMetersUntilRegisteredAgain() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SyncQueueMetrics metrics = new SyncQueueMetrics(registry, () -> 0, name -> 0);
        metrics.registerFunction("echo");

        metrics.admitted("echo");
        metrics.removeFunctionState("echo");

        metrics.rejected("echo");
        metrics.timedOut("echo");
        metrics.recordWait("echo", 10);

        assertThat(registry.find("sync_queue_rejected_total").tag("function", "echo").counter()).isNull();
        assertThat(registry.find("sync_queue_timedout_total").tag("function", "echo").counter()).isNull();
        assertThat(registry.find("sync_queue_wait_seconds").tag("function", "echo").timer()).isNull();

        metrics.registerFunction("echo");
        metrics.admitted("echo");

        assertThat(registry.find("sync_queue_admitted_total").tag("function", "echo").counter()).isNotNull();
        assertThat(registry.find("sync_queue_depth").tag("function", "echo").gauge()).isNotNull();
    }

    @Test
    void liveDepthReadsReservationsInsteadOfAdmissionCallbackOrder() {
        var registry = new SimpleMeterRegistry();
        var depth = new java.util.concurrent.atomic.AtomicInteger(1);
        var metrics = new SyncQueueMetrics(registry, depth::get, name -> depth.get());
        metrics.registerFunction("echo");
        assertThat(registry.get("sync_queue_depth").tag("function", "").gauge().value()).isEqualTo(1);
        depth.set(0); // Dispatch settled before the admission counter callback.
        metrics.admitted("echo");
        assertThat(registry.get("sync_queue_depth").tag("function", "").gauge().value()).isZero();
        assertThat(registry.get("sync_queue_depth").tag("function", "echo").gauge().value()).isZero();
        assertThat(registry.get("sync_queue_admitted_total").tag("function", "echo").counter().count()).isEqualTo(1);
        metrics.removeFunctionState("echo");
        metrics.admitted("echo");
        assertThat(registry.find("sync_queue_depth").tag("function", "echo").gauge()).isNull();
    }
}
