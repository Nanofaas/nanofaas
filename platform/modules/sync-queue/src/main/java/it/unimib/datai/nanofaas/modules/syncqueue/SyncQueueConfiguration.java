package it.unimib.datai.nanofaas.modules.syncqueue;

import it.unimib.datai.nanofaas.controlplane.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.controlplane.config.SyncQueueRuntimeDefaults;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
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
    SyncQueueRuntimeDefaults moduleSyncQueueRuntimeDefaults(SyncQueueProperties props) {
        return props.runtimeDefaults();
    }

    @Bean
    @Primary
    @ConditionalOnBean(SyncQueueService.class)
    SyncQueueGateway moduleSyncQueueGateway(SyncQueueService syncQueueService) {
        return syncQueueService;
    }

    @Bean
    @ConditionalOnBean(SyncQueueService.class)
    FunctionRegistrationListener syncQueueLifecycleListener(SyncQueueService syncQueueService) {
        return new FunctionRegistrationListener() {
            @Override
            public void onRegister(it.unimib.datai.nanofaas.common.model.FunctionSpec spec) {
                syncQueueService.registerFunction(spec.name());
            }

            @Override
            public void onRemove(String functionName) {
                syncQueueService.removeFunctionState(functionName);
            }
        };
    }
}
