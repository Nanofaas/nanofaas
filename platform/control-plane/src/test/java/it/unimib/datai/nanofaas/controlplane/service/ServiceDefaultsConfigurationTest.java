package it.unimib.datai.nanofaas.controlplane.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceDefaultsConfigurationTest {

    @Test
    void registersNoOpBeansWhenMissing() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(Metrics.class, () -> new Metrics(new SimpleMeterRegistry()));
            context.register(ServiceDefaultsConfiguration.class);
            context.refresh();

            assertThat(context.getBean(InvocationEnqueuer.class)).isSameAs(InvocationEnqueuer.noOp());
            assertThat(context.getBean(ScalingMetricsSource.class)).isSameAs(ScalingMetricsSource.noOp());
        }
    }

    @Test
    void doesNotOverrideExplicitBeans() {
        InvocationEnqueuer customInvocationEnqueuer = new InvocationEnqueuer() {
            @Override
            public boolean enqueue(it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask task) {
                return true;
            }

            @Override
            public boolean enabled() {
                return true;
            }
        };
        ScalingMetricsSource customScalingMetricsSource = new ScalingMetricsSource() {
            @Override
            public int queueDepth(String functionName) {
                return 7;
            }

            @Override
            public int inFlight(String functionName) {
                return 3;
            }

            @Override
            public void setEffectiveConcurrency(String functionName, int value) {
                // no-op: the stub's concurrency is fixed, not tracked
            }

            @Override
            public void updateConcurrencyController(String functionName, it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode mode, int targetInFlightPerPod) {
                // no-op: the stub does not adapt concurrency
            }
        };

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(Metrics.class, () -> new Metrics(new SimpleMeterRegistry()));
            context.registerBean(InvocationEnqueuer.class, () -> customInvocationEnqueuer);
            context.registerBean(ScalingMetricsSource.class, () -> customScalingMetricsSource);
            context.register(ServiceDefaultsConfiguration.class);
            context.refresh();

            assertThat(context.getBean(InvocationEnqueuer.class)).isSameAs(customInvocationEnqueuer);
            assertThat(context.getBean(ScalingMetricsSource.class)).isSameAs(customScalingMetricsSource);
        }
    }

    @Test
    void metricsLifecycleListener_removesFunctionMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics metrics = new Metrics(registry);
        FunctionRegistrationListener listener = new ServiceDefaultsConfiguration().metricsLifecycleListener(metrics);

        metrics.dispatch("echo");
        assertThat(registry.find("function_dispatch_total").tag("function", "echo").counter()).isNotNull();

        listener.onRemove("echo");

        assertThat(registry.find("function_dispatch_total").tag("function", "echo").counter()).isNull();

        listener.onRegister(new FunctionSpec(
                "echo", "image", null, java.util.Map.of(), null, 1000, 1, 1, 3, null,
                it.unimib.datai.nanofaas.common.model.ExecutionMode.LOCAL, null, null, null
        ));
        metrics.dispatch("echo");

        assertThat(registry.find("function_dispatch_total").tag("function", "echo").counter()).isNotNull();
    }
}
