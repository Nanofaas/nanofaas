package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.service.Metrics;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadCapacityController;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;

@AutoConfiguration
@AutoConfigureAfter(name = "it.unimib.datai.nanofaas.modules.asyncqueue.AsyncQueueConfiguration")
@ConditionalOnBean({WorkloadMetricsSource.class, WorkloadCapacityController.class, FunctionRegistry.class, Metrics.class})
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
    @Bean
    StaticPerPodConcurrencyController staticPerPodConcurrencyController() {
        return new StaticPerPodConcurrencyController();
    }

    @Bean
    AdaptivePerPodConcurrencyController adaptivePerPodConcurrencyController() {
        return new AdaptivePerPodConcurrencyController();
    }

    @Bean
    ConcurrencyControlMetrics concurrencyControlMetrics(io.micrometer.core.instrument.MeterRegistry registry) {
        return new ConcurrencyControlMetrics(registry);
    }

    @Bean
    ConcurrencyControlCoordinator concurrencyControlCoordinator(WorkloadMetricsSource metricsSource,
                                                                WorkloadCapacityController capacityController,
                                                                ConcurrencyControlMetrics concurrencyMetrics,
                                                                ConcurrencyControlProperties properties,
                                                                StaticPerPodConcurrencyController staticController,
                                                                AdaptivePerPodConcurrencyController adaptiveController) {
        return new ConcurrencyControlCoordinator(metricsSource, capacityController, concurrencyMetrics,
                properties, staticController, adaptiveController);
    }

    @Bean
    ConcurrencyGovernor concurrencyGovernor(FunctionRegistry registry,
                                            Metrics metrics,
                                            ConcurrencyControlCoordinator coordinator,
                                            ConcurrencyControlProperties properties,
                                            WorkloadMetricsSource metricsSource,
                                            WorkloadCapacityController capacityController,
                                            ConcurrencyControlMetrics concurrencyMetrics,
                                            ObjectProvider<ManagedDeploymentCoordinator> deploymentCoordinatorProvider) {
        return new ConcurrencyGovernor(registry, metrics, coordinator, properties,
                deploymentCoordinatorProvider.getIfAvailable(), metricsSource, capacityController,
                concurrencyMetrics);
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
