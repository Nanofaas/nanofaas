package it.unimib.datai.nanofaas.modules.syncqueue;

import it.unimib.datai.nanofaas.controlplane.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(SyncQueueProperties.class)
public class SyncQueueConfiguration {

    @Bean
    @Primary
    @ConditionalOnBean(SyncQueueService.class)
    SyncQueueGateway moduleSyncQueueGateway(SyncQueueService syncQueueService) {
        return syncQueueService;
    }
}
