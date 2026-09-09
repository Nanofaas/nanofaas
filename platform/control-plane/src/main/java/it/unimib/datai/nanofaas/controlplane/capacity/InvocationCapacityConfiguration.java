package it.unimib.datai.nanofaas.controlplane.capacity;

import it.unimib.datai.nanofaas.controlplane.input.RetainedInputEstimator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.codec.CodecCustomizer;

/** Wires the calibrated public invocation-capacity contract. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InvocationCapacityProperties.class)
class InvocationCapacityConfiguration {
    @Bean
    InvocationCapacity invocationCapacity(FunctionCapacityRegistry generations,
                                          InvocationCapacityProperties properties) {
        properties.validate();
        return new InvocationCapacity(
                generations,
                properties.getExecutionsGlobal(), properties.getExecutionsPerFunction(),
                properties.getCanonicalInputBytesGlobal(), properties.getCanonicalInputBytesPerFunction(),
                properties.getPhysicalInputCopyBytesGlobal(), properties.getPhysicalInputCopyBytesPerFunction(),
                properties.getMaxInputReferences());
    }

    @Bean(destroyMethod = "close")
    WaiterCapacity waiterCapacity(FunctionCapacityRegistry generations,
                                  InvocationCapacityProperties properties) {
        properties.validate();
        return new WaiterCapacity(
                generations, properties.getWaitersGlobal(), properties.getWaitersPerFunction());
    }

    @Bean
    RetainedInputEstimator.Limits retainedInputLimits(InvocationCapacityProperties properties) {
        properties.validate();
        return new RetainedInputEstimator.Limits(
                properties.getRetainedInputMaxDepth(),
                properties.getRetainedInputMaxContainerEntries(),
                properties.getRetainedInputMaxVisitedNodes(),
                properties.getRetainedInputMaxBytesPerExecution());
    }

    @Bean
    CodecCustomizer invocationCodecLimit(InvocationCapacityProperties properties) {
        properties.validate();
        int bytes = Math.toIntExact(properties.getIngressBodyBytes());
        return configurer -> configurer.defaultCodecs().maxInMemorySize(bytes);
    }

}
