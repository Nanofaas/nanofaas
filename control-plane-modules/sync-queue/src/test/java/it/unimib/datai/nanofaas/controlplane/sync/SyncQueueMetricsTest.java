package it.unimib.datai.nanofaas.controlplane.sync;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SyncQueueMetricsTest {
    @Test
    void removeFunctionState_removesPerFunctionMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SyncQueueMetrics metrics = new SyncQueueMetrics(registry);

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
        SyncQueueMetrics metrics = new SyncQueueMetrics(registry);

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
}
