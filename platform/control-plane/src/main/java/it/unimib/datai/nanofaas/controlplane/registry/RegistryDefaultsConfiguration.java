package it.unimib.datai.nanofaas.controlplane.registry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;

@Configuration
class RegistryDefaultsConfiguration {

    @Bean
    @Fallback
    @ConditionalOnMissingBean(ImageValidator.class)
    ImageValidator imageValidator() {
        return ImageValidator.noOp();
    }
}
