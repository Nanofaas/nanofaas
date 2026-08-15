package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.service.Metrics;
import it.unimib.datai.nanofaas.controlplane.service.ScalingMetricsSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnBean({ScalingMetricsSource.class, FunctionRegistry.class, Metrics.class})
@EnableConfigurationProperties(ConcurrencyControlProperties.class)
public class ConcurrencyControlConfiguration {

    @Bean
    StaticPerPodConcurrencyController staticPerPodConcurrencyController() {
        return new StaticPerPodConcurrencyController();
    }

    @Bean
    AdaptivePerPodConcurrencyController adaptivePerPodConcurrencyController() {
        return new AdaptivePerPodConcurrencyController();
    }

    @Bean
    ConcurrencyControlCoordinator concurrencyControlCoordinator(ScalingMetricsSource metricsSource,
                                                                ConcurrencyControlProperties properties,
                                                                StaticPerPodConcurrencyController staticController,
                                                                AdaptivePerPodConcurrencyController adaptiveController) {
        return new ConcurrencyControlCoordinator(metricsSource, properties, staticController, adaptiveController);
    }

    @Bean
    ConcurrencyGovernor concurrencyGovernor(FunctionRegistry registry,
                                            Metrics metrics,
                                            ConcurrencyControlCoordinator coordinator,
                                            ConcurrencyControlProperties properties,
                                            ObjectProvider<ManagedDeploymentCoordinator> deploymentCoordinatorProvider) {
        return new ConcurrencyGovernor(registry, metrics, coordinator, properties,
                deploymentCoordinatorProvider.getIfAvailable());
    }

    @Bean
    FunctionRegistrationListener concurrencyControlLifecycleListener(ConcurrencyGovernor governor) {
        return new FunctionRegistrationListener() {
            @Override
            public void onRegister(FunctionSpec spec) {
                // nothing to prime: the governor discovers functions from the registry on each tick
            }

            @Override
            public void onRemove(String functionName) {
                governor.removeFunctionState(functionName);
            }
        };
    }
}
