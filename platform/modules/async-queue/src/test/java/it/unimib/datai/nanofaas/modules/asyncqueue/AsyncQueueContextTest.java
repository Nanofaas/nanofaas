package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.ControlPlaneApplication;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadCapacityController;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What this provider publishes is a contract two other modules are conditional on:
 * autoscaler and concurrency-control both refuse to start without a
 * {@code WorkloadMetricsSource}, and a @ConditionalOnBean that is not satisfied
 * disables a module in silence - no bean, no log line, no failure. The unit tests
 * here build every collaborator with {@code new}, so none of them would notice this
 * configuration going missing.
 */
@SpringBootTest(classes = ControlPlaneApplication.class)
class AsyncQueueContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void publishesTheWorkloadContractTheConsumingModulesConditionOn() {
        assertThat(context.getBeansOfType(WorkloadMetricsSource.class)).hasSize(1);
        assertThat(context.getBeansOfType(WorkloadCapacityController.class)).hasSize(1);
        assertThat(context.getBeansOfType(QueueManager.class)).hasSize(1);
        assertThat(context.getBean(InvocationEnqueuer.class).enabled()).isTrue();
    }
}
