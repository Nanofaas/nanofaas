package it.unimib.datai.nanofaas.controlplane.config;

import it.unimib.datai.nanofaas.controlplane.registry.ImageValidator;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.controlplane.service.ScalingMetricsSource;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;

@Configuration
public class CoreDefaults {

    @Bean
    @ConditionalOnMissingBean(InvocationEnqueuer.class)
    public InvocationEnqueuer invocationEnqueuer() {
        return InvocationEnqueuer.noOp();
    }

    @Bean
    @ConditionalOnMissingBean(ScalingMetricsSource.class)
    public ScalingMetricsSource scalingMetricsSource() {
        return ScalingMetricsSource.noOp();
    }

    @Bean
    @ConditionalOnMissingBean(SyncQueueGateway.class)
    public SyncQueueGateway syncQueueGateway() {
        return SyncQueueGateway.noOp();
    }

    @Bean
    @Fallback
    @ConditionalOnMissingBean(ImageValidator.class)
    public ImageValidator imageValidator() {
        return ImageValidator.noOp();
    }
}
