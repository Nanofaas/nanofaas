package it.unimib.datai.nanofaas.modules.syncqueue;

import it.unimib.datai.nanofaas.controlplane.ControlPlaneApplication;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerControl;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadCapacityController;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sync provider's half of the composed-engine contract (Task 8, issue #208).
 *
 * <p>Since async-queue and sync-queue now share one classpath by design (no more mutual
 * {@code conflicts}), this boots with both modules present and asserts what is invariant
 * regardless of the other module: exactly one {@link WorkloadCapacityController} (there used to
 * be two, one per module) and this module's own {@link SyncQueueGateway} contract. The fix round
 * (C1) gated {@code EngineSyncQueueGateway.enabled()} on the resolved admission profile, not just
 * the runtime flag — with both queue modules on the classpath the default profile is
 * {@code FUNCTION_QUEUE} (the brief's own table: «entrambi → function-queue»), so this test pins
 * {@code nanofaas.admission.profile=sync-queue} explicitly to exercise this module's own gateway
 * being the active one, rather than asserting a profile default this module does not control.
 *
 * <p>The {@code WorkloadMetricsSource} bean is {@code EngineWorkloadMetricsSource}, backed by
 * the composed engine.
 */
@SpringBootTest(classes = ControlPlaneApplication.class,
        properties = {"sync-queue.enabled=true", "nanofaas.admission.profile=sync-queue"})
class SyncQueueContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void publishesAWorkloadMetricsSource() {
        assertThat(context.getBeansOfType(WorkloadMetricsSource.class)).hasSize(1);
    }

    @Test
    void publishesTheWorkloadContractTheConsumingModulesConditionOn() {
        assertThat(context.getBeansOfType(WorkloadCapacityController.class)).hasSize(1);
        assertThat(context.getBean(SyncQueueGateway.class).enabled()).isTrue();
    }

    @Test
    void theEngineIsCreatedWheneverAStrategyModuleIsLoaded() {
        // A4's successor: the shared engine (not a per-module scheduler) is created and running
        // from module load, not gated on sync-queue.enabled, which is runtime-mutable. Without
        // it nothing drains the queue, and the symptom is invocations that simply never dispatch.
        assertThat(context.getBeansOfType(SchedulerControl.class)).hasSize(1);
    }

    @Test
    void theModuleEnqueuerIsTheOnlyOne() {
        // See AsyncQueueContextTest: the core fallback used to be registered alongside the
        // module's own, saved only by the module's @Primary.
        assertThat(context.getBeansOfType(InvocationEnqueuer.class)).hasSize(1);
    }
}