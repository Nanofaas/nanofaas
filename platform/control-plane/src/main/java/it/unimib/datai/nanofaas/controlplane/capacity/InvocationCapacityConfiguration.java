package it.unimib.datai.nanofaas.controlplane.capacity;

import it.unimib.datai.nanofaas.controlplane.input.RetainedInputEstimator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Internal finite safety ceilings; P07e owns public properties and calibrated defaults. */
@Configuration(proxyBeanMethods = false)
class InvocationCapacityConfiguration {
    @Bean
    InvocationCapacity invocationCapacity(FunctionCapacityRegistry generations) {
        return new InvocationCapacity(
                generations,
                100_000, 10_000,
                1L << 30, 256L << 20,
                64);
    }

    @Bean
    RetainedInputEstimator.Limits retainedInputLimits() {
        return new RetainedInputEstimator.Limits(32, 16_384, 65_536, 64L << 20);
    }
}
