package it.unimib.datai.nanofaas.controlplane.sync;

import it.unimib.datai.nanofaas.controlplane.config.SyncQueueRuntimeDefaults;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;

@Configuration
class SyncQueueDefaultsConfiguration {

    @Bean
    @ConditionalOnMissingBean(SyncQueueGateway.class)
    SyncQueueGateway syncQueueGateway() {
        return SyncQueueGateway.noOp();
    }

    @Bean
    @Fallback
    SyncQueueRuntimeDefaults syncQueueRuntimeDefaults() {
        return SyncQueueRuntimeDefaults.defaults();
    }

    @Bean
    @Fallback
    SyncQueueConfigSource syncQueueConfigSource(SyncQueueRuntimeDefaults defaults) {
        return SyncQueueConfigSource.fixed(defaults);
    }
}
