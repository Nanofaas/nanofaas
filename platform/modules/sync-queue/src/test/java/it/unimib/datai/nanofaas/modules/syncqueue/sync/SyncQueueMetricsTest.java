package it.unimib.datai.nanofaas.modules.syncqueue.sync;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SyncQueueMetricsTest {
    @Test
    void rejectionReasonsAndCapacityRemainVisibleBetweenScrapes() {
        var registry = new SimpleMeterRegistry();
        var metrics = new SyncQueueMetrics(registry, () -> 0, name -> 0);
        metrics.registerFunction("a", 2);
        metrics.registerFunction("b", 7);
        metrics.rejected("a", it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason.DEPTH);
        assertThat(registry.get("sync_queue_capacity").tag("function", "a").gauge().value()).isEqualTo(2);
        assertThat(registry.get("sync_queue_capacity").tag("function", "b").gauge().value()).isEqualTo(7);
        assertThat(registry.get("sync_queue_rejected_by_reason_total").tags("function", "a", "reason", "depth").counter().count()).isEqualTo(1);
        assertThat(registry.get("sync_queue_rejected_by_reason_total").tags("function", "b", "reason", "depth").counter().count()).isZero();
        metrics.removeFunctionState("a");
        assertThat(registry.find("sync_queue_capacity").tag("function", "a").gauge()).isNull();
        assertThat(registry.find("sync_queue_rejected_by_reason_total").tag("function", "a").counters()).isEmpty();
    }

    @Test
    void removedNamesDoNotAccumulateInMetricLifecycleState() throws Exception {
        SyncQueueMetrics metrics = new SyncQueueMetrics(new SimpleMeterRegistry(), () -> 0, name -> 0);
        for (int i = 0; i < 1000; i++) {
            String function = "removed-" + i;
            metrics.registerFunction(function, 20);
            metrics.removeFunctionState(function);
        }

        Field field = SyncQueueMetrics.class.getDeclaredField("functions");
        field.setAccessible(true);
        assertThat((Map<?, ?>) field.get(metrics)).isEmpty();
    }

    @Test
    void prometheusRegistryKeepsGlobalAndPerFunctionMeters() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        SyncQueueMetrics metrics = new SyncQueueMetrics(registry, () -> 0, name -> 0);
        metrics.registerFunction("echo", 20);

        assertThat(registry.find("sync_queue_depth").tag("function", "").gauge()).isNotNull();
        assertThat(registry.find("sync_queue_depth").tag("function", "echo").gauge()).isNotNull();
    }

    @Test
    void removeFunctionState_removesPerFunctionMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SyncQueueMetrics metrics = new SyncQueueMetrics(registry, () -> 0, name -> 0);
        metrics.registerFunction("echo", 20);

        metrics.admitted("echo");
        metrics.rejected("echo", it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason.DEPTH);

        assertThat(registry.find("sync_queue_depth").tag("function", "echo").gauge()).isNotNull();
        assertThat(registry.find("sync_queue_admitted_total").tag("function", "echo").counter()).isNotNull();
        assertThat(registry.find("sync_queue_rejected_total").tag("function", "echo").counter()).isNotNull();

        metrics.removeFunctionState("echo");

        assertThat(registry.find("sync_queue_depth").tag("function", "echo").gauge()).isNull();
        assertThat(registry.find("sync_queue_admitted_total").tag("function", "echo").counter()).isNull();
        assertThat(registry.find("sync_queue_rejected_total").tag("function", "echo").counter()).isNull();
        assertThat(registry.find("sync_queue_depth").gauge()).isNotNull();
    }

    @Test
    void removedFunction_doesNotRecreateMetersUntilRegisteredAgain() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SyncQueueMetrics metrics = new SyncQueueMetrics(registry, () -> 0, name -> 0);
        metrics.registerFunction("echo", 20);

        metrics.admitted("echo");
        metrics.removeFunctionState("echo");

        metrics.rejected("echo", it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason.DEPTH);

        assertThat(registry.find("sync_queue_rejected_total").tag("function", "echo").counter()).isNull();

        metrics.registerFunction("echo", 20);
        metrics.admitted("echo");

        assertThat(registry.find("sync_queue_admitted_total").tag("function", "echo").counter()).isNotNull();
        assertThat(registry.find("sync_queue_depth").tag("function", "echo").gauge()).isNotNull();
    }

    /** FunctionService replays onRegister on a queueSize update: the gauge must follow it. */
    @Test
    void reRegistrationUpdatesTheCapacityGauge() {
        var registry = new SimpleMeterRegistry();
        var metrics = new SyncQueueMetrics(registry, () -> 0, name -> 0);
        metrics.registerFunction("echo", 2);
        metrics.admitted("echo");
        metrics.registerFunction("echo", 5);
        assertThat(registry.get("sync_queue_capacity").tag("function", "echo").gauge().value()).isEqualTo(5);
        assertThat(registry.get("sync_queue_admitted_total").tag("function", "echo").counter().count()).isEqualTo(1);
    }

    /** The admission hot path must not wait on another function's (re)registration. */
    @Test
    void admittedAndRejectedDoNotWaitForARegistrationInProgress() throws Exception {
        var registry = new SimpleMeterRegistry();
        var metrics = new SyncQueueMetrics(registry, () -> 0, name -> 0);
        metrics.registerFunction("a", 2);
        var insideRegistration = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        registry.config().onMeterAdded(meter -> {
            if ("b".equals(meter.getId().getTag("function")) && insideRegistration.getCount() > 0) {
                insideRegistration.countDown();
                try {
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        Thread registering = new Thread(() -> metrics.registerFunction("b", 3));
        registering.start();
        try {
            assertThat(insideRegistration.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                metrics.admitted("a");
                metrics.rejected("a", it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason.DEPTH);
            }).get(1, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            registering.join(5_000);
        }
        assertThat(registry.get("sync_queue_admitted_total").tag("function", "a").counter().count()).isEqualTo(1);
        assertThat(registry.get("sync_queue_rejected_total").tag("function", "a").counter().count()).isEqualTo(1);
    }

    @Test
    void liveDepthReadsReservationsInsteadOfAdmissionCallbackOrder() {
        var registry = new SimpleMeterRegistry();
        var depth = new java.util.concurrent.atomic.AtomicInteger(1);
        var metrics = new SyncQueueMetrics(registry, depth::get, name -> depth.get());
        metrics.registerFunction("echo", 20);
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
