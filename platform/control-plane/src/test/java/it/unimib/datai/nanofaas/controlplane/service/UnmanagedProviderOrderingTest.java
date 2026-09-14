package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentReadiness;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionOperationLocks;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class UnmanagedProviderOrderingTest {
    @Test
    void providerAutoConfigurationIsProcessedBeforeManagedConditionAndUnmanagedFallback() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ManagedDeploymentOrchestrationAutoConfiguration.class,
                        UnmanagedDeploymentDefaultsAutoConfiguration.class,
                        ProviderAutoConfiguration.class))
                .withBean(FunctionRegistry.class, FunctionRegistry::new)
                .withBean(FunctionOperationLocks.class, FunctionOperationLocks::new)
                .withBean(FunctionCapacityRegistry.class, FunctionCapacityRegistry::new)
                .withBean(DeploymentWakeUpProperties.class, DeploymentWakeUpProperties::new)
                .withBean(DeploymentProviderResolver.class,
                        () -> new DeploymentProviderResolver(List.of(), new DeploymentProperties(null)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ManagedDeploymentProvider.class);
                    assertThat(context).hasSingleBean(ReplicaStatusSnapshot.class);
                    assertThat(context).hasSingleBean(DeploymentWakeUpGate.class);
                    assertThat(context).hasSingleBean(ManagedDeploymentCoordinator.class);
                    assertThat(context).hasSingleBean(DeploymentReadiness.class);
                    assertThat(context.getBean(DeploymentReadiness.class).isImmediate()).isFalse();
                });
    }

    // This name sorts after UnmanagedDeploymentDefaultsAutoConfiguration, like the
    // optional provider modules. A withBean provider would already exist before sorting
    // and would miss the regression. TestConfiguration excludes this fixture from scans.
    @TestConfiguration(proxyBeanMethods = false)
    @AutoConfiguration
    static class ProviderAutoConfiguration {
        @Bean
        ManagedDeploymentProvider provider() {
            return mock(ManagedDeploymentProvider.class);
        }
    }
}
