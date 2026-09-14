package it.unimib.datai.nanofaas.controlplane.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacity;
import it.unimib.datai.nanofaas.controlplane.capacity.WaiterCapacity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ScheduledThreadPoolExecutor;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nanofaas.metrics.profile", havingValue = "soak")
class SoakMetricsConfiguration {
    @Bean
    Gauge invocationExecutionReservations(MeterRegistry registry, InvocationCapacity capacity) {
        return Gauge.builder(
                        "invocation_execution_reservations",
                        capacity,
                        InvocationCapacity::executionReservedGlobally)
                .register(registry);
    }

    @Bean
    Gauge invocationCanonicalInputBytes(MeterRegistry registry, InvocationCapacity capacity) {
        return Gauge.builder(
                        "invocation_canonical_input_bytes",
                        capacity,
                        InvocationCapacity::inputReservedGlobally)
                .register(registry);
    }

    @Bean
    Gauge invocationPhysicalInputCopyBytes(MeterRegistry registry, InvocationCapacity capacity) {
        return Gauge.builder(
                        "invocation_physical_input_copy_bytes",
                        capacity,
                        InvocationCapacity::physicalInputCopyReservedGlobally)
                .register(registry);
    }

    @Bean
    Gauge executionWaitersRetained(MeterRegistry registry, WaiterCapacity capacity) {
        return Gauge.builder(
                        "execution_waiters_retained",
                        capacity,
                        WaiterCapacity::retainedWaiters)
                .register(registry);
    }

    @Bean
    Gauge executionExpiryQueueDepth(
            MeterRegistry registry,
            @Qualifier("executionExpiryExecutor") ScheduledThreadPoolExecutor executor) {
        return Gauge.builder(
                        "execution_expiry_queue_depth",
                        executor,
                        value -> value.getQueue().size())
                .register(registry);
    }

    @Bean
    Gauge functionCapacityRetiredGenerations(
            MeterRegistry registry, FunctionCapacityRegistry capacity) {
        return Gauge.builder(
                        "function_capacity_retired_generations",
                        capacity,
                        FunctionCapacityRegistry::retiredGenerationCount)
                .register(registry);
    }
}
