package it.unimib.datai.nanofaas.modules.syncqueue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
import it.unimib.datai.nanofaas.modules.syncqueue.scheduler.SyncScheduler;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Regression coverage for review finding P1-5 (docs/control-plane-review-2026-09-05.md,
 * "Attivazione runtime della sync queue senza scheduler"), which the review only verified
 * statically ("percorso verificato staticamente, non avviato il contesto Spring per questa
 * prova"). This test starts the real Spring context to confirm the corrected behaviour.
 *
 * <p>The scheduler is created when the sync-queue module is loaded, even if
 * {@code sync-queue.enabled=false} at boot (A4: the runtime flag only decides the path of
 * NEW invocations; already-admitted work and its retries must keep draining, so the
 * draining worker has to exist from module load). Flipping the runtime flag to
 * {@code enabled=true} must therefore be enough for queued invocations to be dispatched -
 * the bug was that no scheduler existed to drain them at all.
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
    void enablingSyncQueueAtRuntimeDispatchesThroughTheModuleLoadedScheduler() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            // A4: the scheduler exists from module load even with admission disabled at
            // startup. Previously this bean was @ConditionalOnProperty(sync-queue.enabled)
            // and never came into existence for a runtime-only activation.
            SyncScheduler scheduler = context.getBean(SyncScheduler.class);
        // A refreshed context auto-starts SmartLifecycle beans; asserting that is the point,
        // a "start it if it isn't running" guard would make the next assertion unfalsifiable.
        assertThat(scheduler.isRunning()).isTrue();
            assertThat(scheduler.isRunning())
                    .as("the module-loaded scheduler must be running so a runtime "
                            + "activation is drained without a restart")
                    .isTrue();

            InvocationService invocationService = context.getBean(InvocationService.class);
            CountDownLatch dispatched = new CountDownLatch(1);
            doAnswer(invocation -> {
                dispatched.countDown();
                return null;
            }).when(invocationService).dispatch(any(InvocationTask.class));

            MutableSyncQueueConfigSource configSource = context.getBean(MutableSyncQueueConfigSource.class);
            assertThat(configSource.syncQueueEnabled()).isFalse();
            configSource.apply(Map.of(MutableSyncQueueConfigSource.KEY_ENABLED, true));
            assertThat(configSource.syncQueueEnabled()).isTrue();

            // A real queued invocation (as the runtime-activated path would admit one):
            // the running scheduler must pick it up and dispatch it.
            FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null,
                    1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
            InvocationTask task = new InvocationTask("e1", "fn", spec,
                    new InvocationRequest("one", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
            context.getBean(ExecutionStore.class).put(new ExecutionRecord(task.executionId(), task));
            SyncQueueService queue = context.getBean(SyncQueueService.class);
            queue.registerFunction("fn", 1);

            assertThat(context.getBean(SyncQueueInvocationEnqueuer.class).enqueue(task)).isTrue();
            assertThat(dispatched.await(5, TimeUnit.SECONDS))
                    .as("a SyncScheduler must exist once the sync queue is enabled at runtime, "
                            + "otherwise queued invocations are never dispatched")
                    .isTrue();
        });
    }
}
