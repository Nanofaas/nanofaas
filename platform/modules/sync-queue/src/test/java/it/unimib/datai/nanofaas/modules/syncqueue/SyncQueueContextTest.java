package it.unimib.datai.nanofaas.modules.syncqueue;

import it.unimib.datai.nanofaas.controlplane.ControlPlaneApplication;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import it.unimib.datai.nanofaas.modules.syncqueue.scheduler.SyncScheduler;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadCapacityController;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sync provider's half of the same contract, asserted the same way and for the
 * same reason as {@code AsyncQueueContextTest}: autoscaler and concurrency-control
 * declare requires.oneOf=async-queue,sync-queue, so this module has to satisfy
 * their @ConditionalOnBean on its own.
 */
@SpringBootTest(classes = ControlPlaneApplication.class,
        properties = "sync-queue.enabled=true")
// Only under a selection that excludes async-queue: with both providers on the
// classpath the context cannot start at all, which says nothing about either.
@EnabledIfSystemProperty(named = "nanofaas.queue.provider", matches = "sync-queue")
class SyncQueueContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void publishesTheWorkloadContractTheConsumingModulesConditionOn() {
        assertThat(context.getBeansOfType(WorkloadMetricsSource.class)).hasSize(1);
        assertThat(context.getBeansOfType(WorkloadCapacityController.class)).hasSize(1);
        assertThat(context.getBean(SyncQueueGateway.class).enabled()).isTrue();
    }

    @Test
    void theSchedulerIsCreatedWheneverTheModuleIsLoaded() {
        // A4: the draining scheduler is created from module load (not gated on
        // sync-queue.enabled, which is runtime-mutable). Without it nothing drains the
        // queue, and the symptom is invocations that simply never dispatch.
        assertThat(context.getBeansOfType(SyncScheduler.class)).hasSize(1);
    }

    @Test
    void theModuleEnqueuerIsTheOnlyOne() {
        // See AsyncQueueContextTest: the core fallback used to be registered alongside the
        // module's own, saved only by the module's @Primary.
        assertThat(context.getBeansOfType(InvocationEnqueuer.class)).hasSize(1);
    }
}