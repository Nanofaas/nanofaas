package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentReadiness;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The managed-orchestration condition, asserted in a real component-scanned application context.
 *
 * <p>This exists because the isolated {@code ApplicationContextRunner} check cannot see the whole
 * failure mode. That runner registers the auto-configurations and nothing else, so it proves the
 * condition evaluates correctly — but not that the beans are reachable *only* through it. An
 * earlier version of this task shipped the orchestration as a {@code @Configuration} inside the
 * scanned package: the scan registered it unconditionally, every managed bean was created in a
 * profile with no provider at all, and the isolated test still passed. A packaged run caught it.</p>
 *
 * <p>The assertion is written as the invariant rather than as a fixed expectation, so it holds
 * under any module selection this suite happens to run with: managed orchestration exists exactly
 * when a managed deployment provider does.</p>
 */
@SpringBootTest(properties = {
        "nanofaas.registry.path=build/test-scanned-context-deployment-wiring.json",
        "sync-queue.enabled=false",
        "nanofaas.admin.runtime-config.enabled=false"
})
class ScannedContextDeploymentWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void managedOrchestrationExistsExactlyWhenAProviderDoes() {
        boolean provider = context.getBeanNamesForType(ManagedDeploymentProvider.class).length > 0;

        assertThat(context.getBeanNamesForType(ReplicaStatusSnapshot.class).length > 0)
                .as("replica snapshot present == provider present (it owns two refresh pools)")
                .isEqualTo(provider);
        assertThat(context.getBeanNamesForType(DeploymentWakeUpCoordinator.class).length > 0)
                .as("wake-up coordinator present == provider present")
                .isEqualTo(provider);
        assertThat(context.getBeanNamesForType(DeploymentWakeUpGate.class).length > 0)
                .as("wake-up gate present == provider present")
                .isEqualTo(provider);
        assertThat(context.getBeanDefinitionNames())
                .as("the wake-up executor and its scheduler follow the same condition")
                .satisfies(names -> {
                    boolean executors = java.util.Arrays.asList(names).contains("deploymentWakeUpExecutor");
                    assertThat(executors).isEqualTo(provider);
                });
    }

    /** Whatever the selection, there is one coordinator and one readiness, never two of either. */
    @Test
    void thereIsExactlyOneCoordinatorAndOneReadiness() {
        assertThat(context.getBeanNamesForType(ManagedDeploymentCoordinator.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(DeploymentReadiness.class)).hasSize(1);
    }

    /** And without a provider that readiness is the immediate one, not a gate that cannot wake. */
    @Test
    void readinessMatchesTheProfile() {
        boolean provider = context.getBeanNamesForType(ManagedDeploymentProvider.class).length > 0;
        assertThat(context.getBean(DeploymentReadiness.class).isImmediate()).isEqualTo(!provider);
    }

    @Test
    void theFunctionCatalogIsAvailableEitherWay() {
        assertThat(context.getBeanNamesForType(FunctionRegistry.class)).isNotEmpty();
    }
}
