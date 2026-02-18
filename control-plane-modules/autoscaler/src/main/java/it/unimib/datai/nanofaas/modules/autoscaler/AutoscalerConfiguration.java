package it.unimib.datai.nanofaas.modules.autoscaler;

import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.dispatch.KubernetesResourceManager;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.service.ScalingMetricsSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnBean({ScalingMetricsSource.class, MeterRegistry.class, FunctionRegistry.class})
@EnableConfigurationProperties(ScalingProperties.class)
public class AutoscalerConfiguration {

    @Bean
    ScalingMetricsReader scalingMetricsReader(ScalingMetricsSource scalingMetricsSource, MeterRegistry meterRegistry) {
        return new ScalingMetricsReader(scalingMetricsSource, meterRegistry);
    }

    @Bean
    ColdStartTracker coldStartTracker() {
        return new ColdStartTracker();
    }

    @Bean
    TargetLoadMetrics targetLoadMetrics(MeterRegistry meterRegistry) {
        return new TargetLoadMetrics(meterRegistry);
    }

    @Bean
    InternalScaler internalScaler(FunctionRegistry registry,
                                  ScalingMetricsReader metricsReader,
                                  ObjectProvider<KubernetesResourceManager> resourceManagerProvider,
                                  ScalingProperties properties,
                                  ColdStartTracker coldStartTracker) {
        return new InternalScaler(
                registry,
                metricsReader,
                resourceManagerProvider.getIfAvailable(),
                properties,
                coldStartTracker
        );
    }

    @Bean
    FunctionRegistrationListener targetLoadMetricsLifecycleListener(TargetLoadMetrics targetLoadMetrics) {
        return new FunctionRegistrationListener() {
            @Override
            public void onRegister(FunctionSpec spec) {
                targetLoadMetrics.update(spec);
            }

            @Override
            public void onRemove(String functionName) {
                targetLoadMetrics.remove(functionName);
            }
        };
    }
}
