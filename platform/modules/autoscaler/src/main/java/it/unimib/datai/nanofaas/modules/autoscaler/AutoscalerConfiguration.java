package it.unimib.datai.nanofaas.modules.autoscaler;

import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpCoordinator;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
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
// MeterRegistry is deliberately not in this list. It is created by Spring Boot's own
// metrics auto-configuration, and @AutoConfigureAfter orders this class only against the
// two queue providers named above - so the condition was evaluated before the registry's
// bean definition existed, failed, and took the whole module with it. Silently: no bean,
// no InternalScaler, no line in the log, and a campaign that ran 340 requests a second
// against a threshold of 100 while sitting on one replica. The registry is injected into
// the beans below, so if it really were missing the context would say so out loud.
@ConditionalOnBean({WorkloadMetricsSource.class, FunctionRegistry.class})
@EnableConfigurationProperties(ScalingProperties.class)
public class AutoscalerConfiguration {

    @Bean
    ScalingMetricsReader scalingMetricsReader(WorkloadMetricsSource scalingMetricsSource, MeterRegistry meterRegistry) {
        return new ScalingMetricsReader(scalingMetricsSource, meterRegistry);
    }

    @Bean
    ColdStartTracker coldStartTracker() {
        return new ColdStartTracker();
    }

    @Bean
    ScalingDecisionMetrics scalingDecisionMetrics(MeterRegistry meterRegistry) {
        return new ScalingDecisionMetrics(meterRegistry);
    }

    @Bean
    TargetLoadMetrics targetLoadMetrics(MeterRegistry meterRegistry) {
        return new TargetLoadMetrics(meterRegistry);
    }

    @Bean
    InternalScaler internalScaler(FunctionRegistry registry,
                                  ScalingMetricsReader metricsReader,
                                  ObjectProvider<ManagedDeploymentCoordinator> deploymentCoordinatorProvider,
                                  ScalingProperties properties,
                                  ColdStartTracker coldStartTracker,
                                  DeploymentWakeUpCoordinator wakeUpCoordinator,
                                  ScalingDecisionMetrics scalingDecisionMetrics) {
        return new InternalScaler(
                registry,
                metricsReader,
                deploymentCoordinatorProvider.getIfAvailable(),
                properties,
                coldStartTracker,
                wakeUpCoordinator,
                scalingDecisionMetrics
        );
    }

    @Bean
    FunctionRegistrationListener autoscalerLifecycleListener(TargetLoadMetrics targetLoadMetrics,
                                                             ScalingMetricsReader scalingMetricsReader,
                                                             ScalingDecisionMetrics scalingDecisionMetrics,
                                                             InternalScaler internalScaler) {
        return new FunctionRegistrationListener() {
            @Override
            public void onRegister(FunctionSpec spec) {
                targetLoadMetrics.update(spec);
            }

            @Override
            public void onRemove(String functionName) {
                targetLoadMetrics.remove(functionName);
                scalingMetricsReader.removeFunctionState(functionName);
                scalingDecisionMetrics.remove(functionName);
                internalScaler.removeFunctionState(functionName);
            }
        };
    }
}
