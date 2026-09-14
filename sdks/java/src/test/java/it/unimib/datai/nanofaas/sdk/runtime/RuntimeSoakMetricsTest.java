package it.unimib.datai.nanofaas.sdk.runtime;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class RuntimeSoakMetricsTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(SimpleMeterRegistry.class)
            .withBean(HandlerExecutor.class, () -> new HandlerExecutor(1_000, 1))
            .withBean(CallbackDispatcher.class, () -> new CallbackDispatcher(mock(CallbackClient.class), 1))
            .withUserConfiguration(SoakMetricsTestConfiguration.class);

    @Test
    void registersOwnerGaugesOnlyForSoak() {
        contextRunner.run(context -> assertMetricsAbsent(context.getBean(SimpleMeterRegistry.class)));
        contextRunner.withPropertyValues("nanofaas.metrics.profile=basic")
                .run(context -> assertMetricsAbsent(context.getBean(SimpleMeterRegistry.class)));
        contextRunner.withPropertyValues("nanofaas.metrics.profile=advanced")
                .run(context -> assertMetricsAbsent(context.getBean(SimpleMeterRegistry.class)));
        contextRunner.withPropertyValues("nanofaas.metrics.profile=soak").run(context -> {
            SimpleMeterRegistry registry = context.getBean(SimpleMeterRegistry.class);
            assertNotNull(registry.find("runtime_active_handlers").gauge());
            assertNotNull(registry.find("runtime_pending_callbacks").gauge());
            assertNotNull(registry.find("runtime_pending_callback_bytes").gauge());
        });
    }

    @Test
    void callbackGaugesReadAndReleaseAuthoritativeReservations() {
        contextRunner.withPropertyValues("nanofaas.metrics.profile=soak").run(context -> {
            SimpleMeterRegistry registry = context.getBean(SimpleMeterRegistry.class);
            CallbackDispatcher dispatcher = context.getBean(CallbackDispatcher.class);

            CallbackDispatcher.CallbackReservation reservation = dispatcher.tryReserve(64);
            assertNotNull(reservation);
            assertEquals(1.0, registry.get("runtime_pending_callbacks").gauge().value());
            assertEquals(64.0, registry.get("runtime_pending_callback_bytes").gauge().value());

            reservation.close();
            assertEquals(0.0, registry.get("runtime_pending_callbacks").gauge().value());
            assertEquals(0.0, registry.get("runtime_pending_callback_bytes").gauge().value());
        });
    }

    private static void assertMetricsAbsent(SimpleMeterRegistry registry) {
        assertNull(registry.find("runtime_active_handlers").gauge());
        assertNull(registry.find("runtime_pending_callbacks").gauge());
        assertNull(registry.find("runtime_pending_callback_bytes").gauge());
    }

    @Configuration(proxyBeanMethods = false)
    @Import(RuntimeSoakMetrics.class)
    static class SoakMetricsTestConfiguration {
    }
}
