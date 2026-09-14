package it.unimib.datai.nanofaas.controlplane.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacity;
import it.unimib.datai.nanofaas.controlplane.capacity.ResourceOwner;
import it.unimib.datai.nanofaas.controlplane.capacity.WaiterCapacity;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SoakMetricsConfigurationTest {
    private static final List<String> SOAK_METRICS = List.of(
            "invocation_execution_reservations",
            "invocation_canonical_input_bytes",
            "invocation_physical_input_copy_bytes",
            "execution_waiters_retained",
            "execution_expiry_queue_depth",
            "function_capacity_retired_generations"
    );

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(OwnersConfiguration.class, SoakMetricsConfiguration.class);

    @Test
    void registersRealOwnerValuesOnlyForSoak() {
        contextRunner.withPropertyValues("nanofaas.metrics.profile=soak").run(context -> {
            MeterRegistry meters = context.getBean(MeterRegistry.class);
            FunctionCapacityRegistry functions = context.getBean(FunctionCapacityRegistry.class);
            InvocationCapacity invocations = context.getBean(InvocationCapacity.class);
            WaiterCapacity waiters = context.getBean(WaiterCapacity.class);
            ScheduledThreadPoolExecutor expiry = context.getBean(
                    "executionExpiryExecutor", ScheduledThreadPoolExecutor.class);

            functions.register("fn", 2);
            var admission = invocations.reserve("fn", "execution", 11);
            admission.publish();
            var inputCopy = invocations.reserveInputCopy(
                    functions.activeGeneration("fn"),
                    new ResourceOwner(ResourceOwner.Scope.INPUT_COPY, "execution/copy"),
                    7);
            var waiter = waiters.reserve("fn", "execution");
            var lease = functions.tryAcquireLease("fn", 2);
            functions.remove("fn");
            var expiryTask = expiry.schedule(() -> { }, 1, TimeUnit.DAYS);

            assertThat(gauge(meters, "invocation_execution_reservations")).isEqualTo(1);
            assertThat(gauge(meters, "invocation_canonical_input_bytes")).isEqualTo(11);
            assertThat(gauge(meters, "invocation_physical_input_copy_bytes")).isEqualTo(7);
            assertThat(gauge(meters, "execution_waiters_retained")).isEqualTo(1);
            assertThat(gauge(meters, "execution_expiry_queue_depth")).isEqualTo(1);
            assertThat(gauge(meters, "function_capacity_retired_generations")).isEqualTo(1);

            waiter.close();
            inputCopy.close();
            admission.rollback();
            lease.release();
            expiryTask.cancel(false);
            expiry.purge();

            SOAK_METRICS.forEach(name -> assertThat(gauge(meters, name)).isZero());
        });
    }

    @Test
    void doesNotRegisterSoakMetricsForDefaultBasicOrAdvancedProfiles() {
        assertMetricsAbsent(contextRunner);
        assertMetricsAbsent(contextRunner.withPropertyValues("nanofaas.metrics.profile=basic"));
        assertMetricsAbsent(contextRunner.withPropertyValues("nanofaas.metrics.profile=advanced"));
    }

    private static void assertMetricsAbsent(ApplicationContextRunner runner) {
        runner.run(context -> {
            MeterRegistry meters = context.getBean(MeterRegistry.class);
            SOAK_METRICS.forEach(name -> assertThat(meters.find(name).meter()).isNull());
        });
    }

    private static double gauge(MeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnersConfiguration {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        FunctionCapacityRegistry functionCapacityRegistry() {
            return new FunctionCapacityRegistry();
        }

        @Bean
        InvocationCapacity invocationCapacity(FunctionCapacityRegistry functions) {
            return new InvocationCapacity(functions, 10, 10, 100, 100, 100, 100, 2);
        }

        @Bean
        WaiterCapacity waiterCapacity(FunctionCapacityRegistry functions) {
            return new WaiterCapacity(functions, 10, 10);
        }

        @Bean(name = "executionExpiryExecutor", destroyMethod = "shutdownNow")
        ScheduledThreadPoolExecutor executionExpiryExecutor() {
            return new ScheduledThreadPoolExecutor(1);
        }
    }
}
