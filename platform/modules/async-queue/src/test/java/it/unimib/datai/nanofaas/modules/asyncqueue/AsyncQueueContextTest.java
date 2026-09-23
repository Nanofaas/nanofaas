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
 * What this provider publishes is a contract another module is conditional on: a
 * {@code @ConditionalOnBean} that is not satisfied disables a module in silence - no bean, no
 * log line, no failure. The unit tests here build every collaborator with {@code new}, so none
 * of them would notice this configuration going missing.
 *
 * <p>Real scheduling goes through the shared engine ({@code SchedulerConfiguration}), and the
 * {@code WorkloadMetricsSource} bean is {@code EngineWorkloadMetricsSource}. The assertion below
 * documents the real production incident ({@code AutoscalerConfigurationTest}'s B3 campaign)
 * this guards against.
 */
@SpringBootTest(classes = ControlPlaneApplication.class)
class AsyncQueueContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void publishesAWorkloadMetricsSource() {
        assertThat(context.getBeansOfType(WorkloadMetricsSource.class)).hasSize(1);
    }

    @Test
    void publishesTheWorkloadContractTheConsumingModulesConditionOn() {
        assertThat(context.getBeansOfType(WorkloadCapacityController.class)).hasSize(1);
        assertThat(context.getBean(InvocationEnqueuer.class).supportsAsync()).isTrue();
    }

    @Test
    void theModuleEnqueuerIsTheOnlyOne() {
        // The core default is @ConditionalOnMissingBean, but it used to live in a
        // component-scanned @Configuration — evaluated before any auto-configuration, so the
        // condition never saw this module's bean and both were registered. It only worked
        // because the module marks its own @Primary; a future module that forgot to would fail
        // the context outright, and meanwhile every queue profile carried a dead retry pool.
        assertThat(context.getBeansOfType(InvocationEnqueuer.class)).hasSize(1);
    }
}
