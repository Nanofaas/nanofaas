package it.unimib.datai.nanofaas.controlplane.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.service.Metrics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoreDefaultsTest {
    @Test
    void metricsLifecycleListener_removesFunctionMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics metrics = new Metrics(registry);
        CoreDefaults defaults = new CoreDefaults();
        FunctionRegistrationListener listener = defaults.metricsLifecycleListener(metrics);

        metrics.dispatch("echo");
        assertThat(registry.find("function_dispatch_total").tag("function", "echo").counter()).isNotNull();

        listener.onRemove("echo");

        assertThat(registry.find("function_dispatch_total").tag("function", "echo").counter()).isNull();

        listener.onRegister(new it.unimib.datai.nanofaas.common.model.FunctionSpec(
                "echo", "image", null, java.util.Map.of(), null, 1000, 1, 1, 3, null,
                it.unimib.datai.nanofaas.common.model.ExecutionMode.LOCAL, null, null, null
        ));
        metrics.dispatch("echo");

        assertThat(registry.find("function_dispatch_total").tag("function", "echo").counter()).isNotNull();
    }
}
