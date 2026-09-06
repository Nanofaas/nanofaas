package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ServiceDefaultsConfiguration {

    @Bean
    FunctionRegistrationListener metricsLifecycleListener(Metrics metrics) {
        return new FunctionRegistrationListener() {
            @Override
            public void onRegister(it.unimib.datai.nanofaas.common.model.FunctionSpec spec) {
                metrics.registerFunction(spec.name());
            }

            @Override
            public void onRemove(String functionName) {
                metrics.removeFunction(functionName);
            }
        };
    }
}
