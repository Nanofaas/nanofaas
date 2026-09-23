package it.unimib.datai.nanofaas.modules.autoscaler;

import it.unimib.datai.nanofaas.controlplane.ControlPlaneApplication;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;
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
 * <p>This test was held {@code @Disabled} from Task 8's fix round to Task 11 of issue #208: the
 * engine composition retired the per-module {@code WorkloadMetricsSource} beans (async-queue's
 * and sync-queue's own) with no replacement, which genuinely turned this guard red — the correct,
 * loud signal that autoscaler stopped starting. Task 11 restored the bean as
 * {@code EngineWorkloadMetricsSource} and the annotation is gone: the test runs again.
 *
 * <p>It still fails under the gate's module selection, for an unrelated and pre-existing reason.
 * The gate runs {@code -PcontrolPlaneModules=async-queue,sync-queue,runtime-config}, which selects
 * no deployment provider, so no {@code DeploymentWakeUpControl} bean exists and the context fails
 * at {@code autoscalerLifecycleListener} before any assertion here is reached ("No qualifying bean
 * of type 'DeploymentWakeUpControl' available"). That is a property of the selection, not of this
 * test. Do not delete it: it documents the real production incident it exists to catch.
 */
@SpringBootTest(classes = ControlPlaneApplication.class)
class AutoscalerConfigurationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void theScalerStartsWhenAQueueProviderSuppliesWorkloadMetrics() {
        assertThat(context.getBeansOfType(WorkloadMetricsSource.class)).hasSize(1);
        assertThat(context.getBeansOfType(InternalScaler.class)).hasSize(1);
        assertThat(context.getBeansOfType(ScalingDecisionMetrics.class)).hasSize(1);
    }
}
