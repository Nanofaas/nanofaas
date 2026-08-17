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

    /**
     * Refuses to start when no module supplies real queue state.
     *
     * <p>{@code @ConditionalOnBean} above cannot catch this: the core always registers a no-op
     * source, so the condition holds even when nothing produces one. The governor would then run
     * against a source reporting depth 0 and in-flight 0 forever, and — worse — every limit it
     * computed would be written into that same no-op and enforced by nobody. The module would be
     * entirely inert while its metrics claimed otherwise.
     */
    public ConcurrencyControlConfiguration(ScalingMetricsSource metricsSource) {
        if (!metricsSource.enabled()) {
            throw new IllegalStateException(
                    "concurrency-control needs a module that supplies queue state (async-queue); "
                            + "without one the governor reads zeroes and the limits it computes "
                            + "are enforced by nothing. Add async-queue to the module selection.");
        }
    }

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
                                            ScalingMetricsSource metricsSource,
                                            ObjectProvider<ManagedDeploymentCoordinator> deploymentCoordinatorProvider) {
        return new ConcurrencyGovernor(registry, metrics, coordinator, properties,
                deploymentCoordinatorProvider.getIfAvailable(), metricsSource);
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
