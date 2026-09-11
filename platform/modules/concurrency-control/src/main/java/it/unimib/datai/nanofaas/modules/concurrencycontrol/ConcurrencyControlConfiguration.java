package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.service.InvocationObservations;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadCapacityController;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;

@AutoConfiguration
@AutoConfigureAfter(name = {
        "it.unimib.datai.nanofaas.modules.asyncqueue.AsyncQueueConfiguration",
        "it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueConfiguration"
})
@ConditionalOnBean({WorkloadMetricsSource.class, WorkloadCapacityController.class, FunctionRegistry.class, InvocationObservations.class})
@EnableConfigurationProperties(ConcurrencyControlProperties.class)
public class ConcurrencyControlConfiguration {

    /** Starts only when a queue provider supplies workload readings and capacity control. */
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
    ConcurrencyControlCoordinator concurrencyControlCoordinator(WorkloadCapacityController capacityController,
                                                                ConcurrencyControlMetrics concurrencyMetrics,
                                                                ConcurrencyControlProperties properties,
                                                                StaticPerPodConcurrencyController staticController,
                                                                AdaptivePerPodConcurrencyController adaptiveController) {
        return new ConcurrencyControlCoordinator(capacityController, concurrencyMetrics,
                properties, staticController, adaptiveController);
    }

    @Bean
    ConcurrencyGovernor concurrencyGovernor(FunctionRegistry registry,
                                            InvocationObservations metrics,
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
