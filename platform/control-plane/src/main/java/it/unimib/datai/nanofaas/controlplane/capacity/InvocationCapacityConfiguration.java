package it.unimib.datai.nanofaas.controlplane.capacity;

import it.unimib.datai.nanofaas.controlplane.input.RetainedInputEstimator;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.http.codec.CodecCustomizer;

/**
 * Wires the calibrated public invocation-capacity contract.
 *
 * <p>{@link InvocationCapacityProperties} moved into the mandatory {@code :execution-runtime}
 * library (issue #208, Task 9) and became a pure POJO with no {@code @ConfigurationProperties}
 * of its own, since that library must not depend on Spring. This is the control plane's binding
 * point instead: every default, validation and public key stays exactly as it was. The bare
 * {@code @EnableConfigurationProperties} activates {@code @ConfigurationProperties} binding for
 * the {@code @Bean} method below wherever this configuration is loaded on its own (e.g. in an
 * {@code ApplicationContextRunner} test), matching production behaviour, where
 * {@code ControlPlaneApplication}'s {@code @ConfigurationPropertiesScan} already does so.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties
class InvocationCapacityConfiguration {
    @Bean
    @ConfigurationProperties(prefix = "nanofaas.invocation-capacity")
    InvocationCapacityProperties invocationCapacityProperties() {
        return new InvocationCapacityProperties();
    }

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
