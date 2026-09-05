package it.unimib.datai.nanofaas.modules.syncqueue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
import it.unimib.datai.nanofaas.modules.syncqueue.scheduler.SyncScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Regression coverage for review finding P1-5 (docs/control-plane-review-2026-09-05.md,
 * "Attivazione runtime della sync queue senza scheduler"), which the review only verified
 * statically ("percorso verificato staticamente, non avviato il contesto Spring per questa
 * prova"). This test starts the real Spring context to confirm it.
 *
 * <p>{@link SyncQueueConfiguration#syncScheduler} is gated by
 * {@code @ConditionalOnProperty(prefix = "sync-queue", name = "enabled")}, evaluated once at
 * context refresh. {@link MutableSyncQueueConfigSource} lets {@code enabled} be flipped at
 * runtime (via the runtime-config admin API in production), but nothing re-evaluates the
 * conditional bean when that happens: a control plane that boots with the queue disabled and
 * is later switched on at runtime never gets a scheduler, so accepted items queue up with no
 * consumer.
 */
class SyncQueueRuntimeActivationRegressionTest {

    @Configuration
    static class TestSupport {
        @Bean
        ExecutionStore executionStore() {
            return new ExecutionStore();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        InvocationService invocationService() {
            return mock(InvocationService.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestSupport.class, SyncQueueConfiguration.class)
            .withPropertyValues(
                    "sync-queue.enabled=false",
                    "sync-queue.admission-enabled=false",
                    "sync-queue.max-depth=10",
                    "sync-queue.max-estimated-wait=1s",
                    "sync-queue.max-queue-wait=1s",
                    "sync-queue.retry-after-seconds=1",
                    "sync-queue.throughput-window=1s",
                    "sync-queue.per-function-min-samples=1");

    @Test
    void enablingSyncQueueAtRuntimeShouldCreateTheMissingScheduler() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            // Starts disabled: no scheduler bean, as designed.
            assertThat(context.getBeansOfType(SyncScheduler.class)).isEmpty();

            MutableSyncQueueConfigSource configSource = context.getBean(MutableSyncQueueConfigSource.class);
            configSource.apply(Map.of(MutableSyncQueueConfigSource.KEY_ENABLED, true));
            assertThat(configSource.syncQueueEnabled()).isTrue();

            // BUG (still reproduces on current code): flipping the runtime flag does not
            // create the scheduler that drains the queue. New sync invocations get admitted
            // and queued (SyncQueueService/SyncQueueInvocationEnqueuer act purely on the
            // mutable flag) with nothing consuming them, ever.
            assertThat(context.getBeansOfType(SyncScheduler.class))
                    .as("a SyncScheduler must exist once the sync queue is enabled at runtime, "
                            + "otherwise queued invocations are never dispatched")
                    .isNotEmpty();
        });
    }
}
