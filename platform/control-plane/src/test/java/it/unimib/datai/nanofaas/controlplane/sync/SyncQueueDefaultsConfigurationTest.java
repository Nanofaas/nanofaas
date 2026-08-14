package it.unimib.datai.nanofaas.controlplane.sync;

import it.unimib.datai.nanofaas.controlplane.config.SyncQueueRuntimeDefaults;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class SyncQueueDefaultsConfigurationTest {

    @Test
    void registersFallbackQueueBeans() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(SyncQueueDefaultsConfiguration.class);
            context.refresh();

            assertThat(context.getBean(SyncQueueGateway.class)).isSameAs(SyncQueueGateway.noOp());
            assertThat(context.getBean(SyncQueueRuntimeDefaults.class)).isEqualTo(SyncQueueRuntimeDefaults.defaults());
            assertThat(context.getBean(SyncQueueConfigSource.class).getClass()).isEqualTo(SyncQueueConfigSource.fixed(SyncQueueRuntimeDefaults.defaults()).getClass());
        }
    }

    @Test
    void doesNotOverrideExplicitGateway() {
        SyncQueueGateway customGateway = new SyncQueueGateway() {
            @Override
            public void enqueueOrThrow(it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask task) {
                // no-op: the stub gateway never enqueues
            }

            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public int retryAfterSeconds() {
                return 9;
            }
        };

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SyncQueueGateway.class, () -> customGateway);
            context.register(SyncQueueDefaultsConfiguration.class);
            context.refresh();

            assertThat(context.getBean(SyncQueueGateway.class)).isSameAs(customGateway);
        }
    }

    @Test
    void explicitRuntimeDefaultsAndConfigSourceTakePrecedenceOverFallbacks() {
        SyncQueueRuntimeDefaults runtimeDefaults = new SyncQueueRuntimeDefaults(
                true, true, java.time.Duration.ofSeconds(3), java.time.Duration.ofSeconds(4), 5
        );
        SyncQueueConfigSource configSource = SyncQueueConfigSource.fixed(runtimeDefaults);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SyncQueueRuntimeDefaults.class, () -> runtimeDefaults);
            context.registerBean(SyncQueueConfigSource.class, () -> configSource);
            context.register(SyncQueueDefaultsConfiguration.class);
            context.refresh();

            assertThat(context.getBean(SyncQueueRuntimeDefaults.class)).isSameAs(runtimeDefaults);
            assertThat(context.getBean(SyncQueueConfigSource.class)).isSameAs(configSource);
        }
    }
}
