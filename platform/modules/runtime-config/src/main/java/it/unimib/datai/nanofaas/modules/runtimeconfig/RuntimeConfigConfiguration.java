package it.unimib.datai.nanofaas.modules.runtimeconfig;

import it.unimib.datai.nanofaas.controlplane.config.RuntimeConfigExtension;
import it.unimib.datai.nanofaas.controlplane.capacity.AdmissionLimitsControl;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;

@AutoConfiguration
public class RuntimeConfigConfiguration {

    @Bean
    RuntimeConfigExtension controlPlaneRuntimeConfigExtension(AdmissionLimitsControl limits) {
        return new ControlPlaneRuntimeConfigExtension(limits);
    }

    @Bean
    RuntimeConfigRegistry runtimeConfigRegistry(java.util.List<RuntimeConfigExtension> extensions) {
        return new RuntimeConfigRegistry(extensions);
    }

    @Bean
    RuntimeConfigService runtimeConfigService(RuntimeConfigRegistry registry,
                                               MeterRegistry meterRegistry) {
        return new RuntimeConfigService(registry, meterRegistry);
    }

    @Bean
    @ConditionalOnProperty(name = "nanofaas.admin.runtime-config.enabled", havingValue = "true")
    AdminRuntimeConfigController adminRuntimeConfigController(RuntimeConfigService configService) {
        return new AdminRuntimeConfigController(configService);
    }
}
