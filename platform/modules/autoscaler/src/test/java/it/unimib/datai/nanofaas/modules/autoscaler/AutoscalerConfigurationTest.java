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
