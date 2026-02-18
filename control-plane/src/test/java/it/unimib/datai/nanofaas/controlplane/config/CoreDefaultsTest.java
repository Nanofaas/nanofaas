package it.unimib.datai.nanofaas.controlplane.config;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.controlplane.registry.ImageValidator;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.controlplane.service.ScalingMetricsSource;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CoreDefaultsTest {

    @Test
    void registersNoOpBeansWhenMissing() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(CoreDefaults.class)) {
            InvocationEnqueuer invocationEnqueuer = context.getBean(InvocationEnqueuer.class);
            ScalingMetricsSource scalingMetricsSource = context.getBean(ScalingMetricsSource.class);
            SyncQueueGateway syncQueueGateway = context.getBean(SyncQueueGateway.class);
            ImageValidator imageValidator = context.getBean(ImageValidator.class);

            assertThat(invocationEnqueuer).isSameAs(InvocationEnqueuer.noOp());
            assertThat(scalingMetricsSource).isSameAs(ScalingMetricsSource.noOp());
            assertThat(syncQueueGateway).isSameAs(SyncQueueGateway.noOp());
            assertThatCode(() -> imageValidator.validate(null)).doesNotThrowAnyException();
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

            @Override
            public void decrementInFlight(String functionName) {
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
            }

            @Override
            public void updateConcurrencyController(String functionName, ConcurrencyControlMode mode, int targetInFlightPerPod) {
            }
        };
        SyncQueueGateway customSyncQueueGateway = new SyncQueueGateway() {
            @Override
            public void enqueueOrThrow(it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask task) {
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
        ImageValidator customImageValidator = spec -> {};

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(InvocationEnqueuer.class, () -> customInvocationEnqueuer);
            context.registerBean(ScalingMetricsSource.class, () -> customScalingMetricsSource);
            context.registerBean(SyncQueueGateway.class, () -> customSyncQueueGateway);
            context.registerBean(ImageValidator.class, () -> customImageValidator);
            context.register(CoreDefaults.class);
            context.refresh();

            assertThat(context.getBean(InvocationEnqueuer.class)).isSameAs(customInvocationEnqueuer);
            assertThat(context.getBean(ScalingMetricsSource.class)).isSameAs(customScalingMetricsSource);
            assertThat(context.getBean(SyncQueueGateway.class)).isSameAs(customSyncQueueGateway);
            assertThat(context.getBean(ImageValidator.class)).isSameAs(customImageValidator);
        }
    }
}
