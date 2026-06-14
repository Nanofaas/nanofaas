package it.unimib.datai.nanofaas.modules.runtimeconfig;

import it.unimib.datai.nanofaas.controlplane.config.SyncQueueRuntimeDefaults;
import it.unimib.datai.nanofaas.controlplane.service.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnBean(RateLimiter.class)
public class RuntimeConfigConfiguration {

    @Bean
    RuntimeConfigService runtimeConfigService(RateLimiter rateLimiter, SyncQueueRuntimeDefaults syncQueueDefaults) {
        return new RuntimeConfigService(rateLimiter, syncQueueDefaults);
    }

    @Bean
    RuntimeConfigValidator runtimeConfigValidator() {
        return new RuntimeConfigValidator();
    }

    @Bean
    RuntimeConfigApplier runtimeConfigApplier(
            RateLimiter rateLimiter,
            MeterRegistry meterRegistry) {
        return new RuntimeConfigApplier(rateLimiter, meterRegistry);
    }

    @Bean
    @ConditionalOnProperty(name = "nanofaas.admin.runtime-config.enabled", havingValue = "true")
    AdminRuntimeConfigController adminRuntimeConfigController(
            RuntimeConfigService configService,
            RuntimeConfigValidator validator,
            RuntimeConfigApplier applier) {
        return new AdminRuntimeConfigController(configService, validator, applier);
    }
}
