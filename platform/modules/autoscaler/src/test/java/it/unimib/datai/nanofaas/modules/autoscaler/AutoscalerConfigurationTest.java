package it.unimib.datai.nanofaas.modules.autoscaler;

import it.unimib.datai.nanofaas.controlplane.ControlPlaneApplication;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The B3 campaign of 2026-08-29 ran 340 requests a second against a threshold of
 * 100 and never left one replica, and the control-plane log carried neither of
 * the two lines InternalScaler.start() always writes. Nothing in this module
 * could have caught that: its tests construct InternalScaler directly, and no
 * queue provider was on the test classpath, so the one thing that decides
 * whether this module starts - a WorkloadMetricsSource bean - was never present.
 *
 * <p>Disabled by Task 8's fix round (issue #208): the engine composition retires the per-module
 * {@code WorkloadMetricsSource} beans (async-queue's and sync-queue's own), and Task 11 owns
 * their engine-backed replacement. Until then this guard is genuinely red, which is the correct,
 * loud signal that autoscaler does not start — see
 * {@code SchedulerConfiguration.schedulerWorkloadMetricsSourcePresenceCheck}'s log.warn for the
 * runtime-visible half of the same signal. Do not delete this test: it documents the real
 * production incident it exists to catch.
 */
@SpringBootTest(classes = ControlPlaneApplication.class)
class AutoscalerConfigurationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @Disabled("Task 11 — no WorkloadMetricsSource while the engine composition lands")
    void theScalerStartsWhenAQueueProviderSuppliesWorkloadMetrics() {
        assertThat(context.getBeansOfType(WorkloadMetricsSource.class)).hasSize(1);
        assertThat(context.getBeansOfType(InternalScaler.class)).hasSize(1);
        assertThat(context.getBeansOfType(ScalingDecisionMetrics.class)).hasSize(1);
    }
}
