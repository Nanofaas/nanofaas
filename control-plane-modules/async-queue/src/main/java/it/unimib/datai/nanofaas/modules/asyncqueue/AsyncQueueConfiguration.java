package it.unimib.datai.nanofaas.modules.asyncqueue;

import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
import it.unimib.datai.nanofaas.controlplane.service.ScalingMetricsSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@ConditionalOnBean({MeterRegistry.class, InvocationService.class})
public class AsyncQueueConfiguration {

    @Bean
    QueueManager queueManager(MeterRegistry meterRegistry) {
        return new QueueManager(meterRegistry);
    }

    @Bean
    Scheduler scheduler(QueueManager queueManager, InvocationService invocationService) {
        return new Scheduler(queueManager, invocationService);
    }

    @Bean
    @Primary
    InvocationEnqueuer asyncQueueInvocationEnqueuer(QueueManager queueManager) {
        return new QueueBackedEnqueuer(queueManager);
    }

    @Bean
    @Primary
    ScalingMetricsSource asyncQueueScalingMetricsSource(QueueManager queueManager) {
        return new QueueBackedMetricsSource(queueManager);
    }

    @Bean
    FunctionRegistrationListener queueLifecycleListener(QueueManager queueManager) {
        return new FunctionRegistrationListener() {
            @Override
            public void onRegister(it.unimib.datai.nanofaas.common.model.FunctionSpec spec) {
                queueManager.getOrCreate(spec);
            }

            @Override
            public void onRemove(String functionName) {
                queueManager.remove(functionName);
            }
        };
    }
}
