package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import it.unimib.datai.nanofaas.controlplane.ControlPlaneApplication;
import it.unimib.datai.nanofaas.workloadmetrics.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ControlPlaneApplication.class,
        properties = "nanofaas.metrics.profile=advanced")
@EnabledIfSystemProperty(named = "nanofaas.queue.provider", matches = "sync-queue")
class SyncConcurrencyControlE2eTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void startsWithExactlyOneSyncWorkloadProvider() {
        assertThat(applicationContext.getBeansOfType(FunctionCapacityRegistry.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(WorkloadMetricsSource.class)).hasSize(1);
    }
}
