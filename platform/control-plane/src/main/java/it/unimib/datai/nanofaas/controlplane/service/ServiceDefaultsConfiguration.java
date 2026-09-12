package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacity;
import it.unimib.datai.nanofaas.controlplane.capacity.WaiterCapacity;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ServiceDefaultsConfiguration {

    /**
     * The hot-limit port the optional runtime-config module drives. It is declared here, beside
     * the rate limiter it needs, so that a minimal capacity slice does not acquire a dependency
     * on the service layer.
     */
    @Bean
    HotAdmissionLimits hotAdmissionLimits(RateLimiter rateLimiter,
                                         InvocationCapacity invocationCapacity,
                                         WaiterCapacity waiterCapacity) {
        return new HotAdmissionLimits(rateLimiter, invocationCapacity, waiterCapacity);
    }

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
