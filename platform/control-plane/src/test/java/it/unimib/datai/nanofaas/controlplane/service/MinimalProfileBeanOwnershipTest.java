package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentReadiness;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionOperationLocks;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P22: managed deployment orchestration exists exactly when a managed deployment provider does.
 *
 * <p>The beans this asserts about are the ones that own threads or in-flight state: the replica
 * snapshot holds two refresh pools, the wake-up executor is four threads plus a scheduler, and the
 * gate holds wake-up state and timers. In a LOCAL/EXTERNAL control plane none of them can ever do
 * any work, so creating them spends threads on nothing and gives shutdown paths to drain that
 * correspond to no activity that ever happened.</p>
 *
 * <p>The condition is exercised directly rather than through a Gradle module selection, so the
 * result does not depend on which profile happens to run this suite: the same runner is used with
 * and without a provider bean.</p>
 */
class MinimalProfileBeanOwnershipTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ManagedDeploymentOrchestrationAutoConfiguration.class,
                    UnmanagedDeploymentDefaultsAutoConfiguration.class))
            // Explicit suppliers: the runner would otherwise pick each type's greediest
            // constructor and demand collaborators this test has no reason to stand up.
            .withBean(FunctionRegistry.class, FunctionRegistry::new)
            .withBean(FunctionOperationLocks.class, FunctionOperationLocks::new)
            .withBean(FunctionCapacityRegistry.class, FunctionCapacityRegistry::new)
            .withBean(DeploymentWakeUpProperties.class, DeploymentWakeUpProperties::new)
            .withBean(DeploymentProperties.class, () -> new DeploymentProperties(null))
            .withBean(DeploymentProviderResolver.class,
                    () -> new DeploymentProviderResolver(List.of(), new DeploymentProperties(null)));

    @Test
    void withoutAProviderNoThreadOwningOrchestrationIsCreated() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ReplicaStatusSnapshot.class);
            assertThat(context).doesNotHaveBean(DeploymentWakeUpCoordinator.class);
            assertThat(context).doesNotHaveBean(DeploymentWakeUpGate.class);
            assertThat(context.getBeanNamesForType(java.util.concurrent.ScheduledExecutorService.class))
                    .doesNotContain("deploymentWakeUpTimeoutScheduler");
            assertThat(context.getBeanDefinitionNames()).doesNotContain("deploymentWakeUpExecutor");
        });
    }

    /**
     * The coordinator stays: the function service needs it to answer managed operations on a
     * catalog restored from disk, whose entries can name a backend this build no longer has. What
     * must not stay is a snapshot owning refresh pools — the coordinator here is built on one that
     * owns none, so shutdown has nothing to drain and every replica reading is honestly
     * UNAVAILABLE rather than a fabricated zero.
     */
    /**
     * Generations have one authority, the application's capacity registry. A coordinator on a
     * registry of its own sees no function ever registered there, so every reader of
     * {@code generationOf} (forecasting, the scaler, offload) would find none.
     */
    @Test
    void withoutAProviderTheCoordinatorReadsGenerationsFromTheApplicationsCapacity() {
        runner.run(context -> {
            FunctionRegistry functions = context.getBean(FunctionRegistry.class);
            FunctionCapacityRegistry capacity = context.getBean(FunctionCapacityRegistry.class);
            functions.put(new FunctionSpec("fn", "image", null, null, null, 1000, 1, 10, 0,
                    "http://function", it.unimib.datai.nanofaas.common.model.ExecutionMode.EXTERNAL,
                    null, null, null));
            capacity.register("fn", 1);

            assertThat(context.getBean(ManagedDeploymentCoordinator.class)
                    .generationOf(functions.getRegistered("fn").orElseThrow()))
                    .isNotNull()
                    .isEqualTo(capacity.activeGeneration("fn"));
        });
    }

    @Test
    void withoutAProviderTheCoordinatorIsStillPresentAndOwnsNoRefreshPool() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ManagedDeploymentCoordinator.class);
            assertThat(context).doesNotHaveBean(ReplicaStatusSnapshot.class);
        });
    }

    /** The dispatch path receives a readiness value, not a null to branch on. */
    @Test
    void withoutAProviderReadinessIsImmediate() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(DeploymentReadiness.class);
            assertThat(context.getBean(DeploymentReadiness.class).isImmediate()).isTrue();
        });
    }

    @Test
    void withAProviderTheFullOrchestrationIsCreatedAndReadinessIsTheGate() {
        runner.withBean(ManagedDeploymentProvider.class, StubProvider::new).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ReplicaStatusSnapshot.class);
            assertThat(context).hasSingleBean(DeploymentWakeUpCoordinator.class);
            assertThat(context).hasSingleBean(DeploymentWakeUpGate.class);
            assertThat(context.getBeanDefinitionNames())
                    .contains("deploymentWakeUpExecutor", "deploymentWakeUpTimeoutScheduler");
            // One coordinator, not two: the unmanaged fallback must stand down here, or the
            // context would hold a second owner of the same managed state.
            assertThat(context).hasSingleBean(ManagedDeploymentCoordinator.class);
            assertThat(context).hasSingleBean(DeploymentReadiness.class);
            assertThat(context.getBean(DeploymentReadiness.class))
                    .isSameAs(context.getBean(DeploymentWakeUpGate.class));
            assertThat(context.getBean(DeploymentReadiness.class).isImmediate()).isFalse();
        });
    }

    /** Dropping managed orchestration must not take the function catalog with it. */
    @Test
    void theFunctionCatalogRemainsAvailableEitherWay() {
        runner.run(context -> assertThat(context).hasSingleBean(FunctionRegistry.class));
        runner.withBean(ManagedDeploymentProvider.class, StubProvider::new)
                .run(context -> assertThat(context).hasSingleBean(FunctionRegistry.class));
    }

    /** Present only to satisfy the condition; no test here calls it. */
    private static final class StubProvider implements ManagedDeploymentProvider {
        @Override public String backendId() { return "stub"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean supports(FunctionSpec spec) { return true; }
        @Override public ProvisionResult provision(FunctionSpec spec) { throw new UnsupportedOperationException(); }
        @Override public ProvisionResult reconcile(FunctionSpec spec, int desiredReplicas,
                                                  java.util.Map<String, String> deploymentObjects) {
            throw new UnsupportedOperationException();
        }
        @Override public void deprovision(String functionName) { throw new UnsupportedOperationException(); }
        @Override public void setReplicas(String functionName, int replicas) { throw new UnsupportedOperationException(); }
        @Override public int getReadyReplicas(String functionName) { throw new UnsupportedOperationException(); }
    }
}
