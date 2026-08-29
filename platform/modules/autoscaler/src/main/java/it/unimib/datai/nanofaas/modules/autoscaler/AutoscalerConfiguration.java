package it.unimib.datai.nanofaas.modules.autoscaler;

import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentCoordinator;
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
@AutoConfigureAfter(name = "it.unimib.datai.nanofaas.modules.asyncqueue.AsyncQueueConfiguration")
@ConditionalOnBean({WorkloadMetricsSource.class, MeterRegistry.class, FunctionRegistry.class})
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
