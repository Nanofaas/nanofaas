package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
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

    /**
     * The single, core-owned capacity registry (P06): direct admission, the queue modules
     * and the governor all bound against the same authority. {@code @ConditionalOnMissingBean}
     * keeps a future module free to replace it, but the core always has one, so the
     * no-queue profile is bounded too.
     */
    @Bean
    @ConditionalOnMissingBean(FunctionCapacityRegistry.class)
    FunctionCapacityRegistry functionCapacityRegistry() {
        return new FunctionCapacityRegistry();
    }
    @Bean
    FunctionRegistrationListener coreCapacityRegistration(FunctionCapacityRegistry registry) {
        return new FunctionRegistrationListener() {
            @Override public void onRegister(it.unimib.datai.nanofaas.common.model.FunctionSpec spec) {
                registry.register(spec.name(), spec.concurrency());
            }
            @Override public void onRemove(String functionName) {
                registry.remove(functionName);
            }
        };
    }

}
