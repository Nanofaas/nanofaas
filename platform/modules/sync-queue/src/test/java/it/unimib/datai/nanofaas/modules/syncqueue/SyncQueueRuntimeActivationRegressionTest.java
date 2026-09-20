package it.unimib.datai.nanofaas.modules.syncqueue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationDispatch;
import it.unimib.datai.nanofaas.controlplane.service.EngineSyncQueueGateway;
import it.unimib.datai.nanofaas.controlplane.service.SchedulerConfiguration;
import it.unimib.datai.nanofaas.controlplane.service.SchedulerLifecycleAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
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
 * "Runtime activation of the sync queue with no scheduler"), which the review only verified
 * statically. This test starts the real Spring context to confirm the corrected behaviour.
 *
 * <p>Updated for Task 8 (issue #208): the draining worker used to be {@code SyncScheduler},
 * created unconditionally by {@code SyncQueueConfiguration} regardless of {@code sync-queue.enabled}
 * (A4: the runtime flag only decides the path of NEW invocations; already-admitted work and its
 * retries must keep draining). That worker is retired; the SAME property is now provided by the
 * single composed {@link it.unimib.datai.nanofaas.execution.SchedulerEngine}, started by
 * {@link SchedulerLifecycleAdapter} from {@code SchedulerConfiguration} the moment a
 * {@code SchedulingStrategy} exists — not gated on {@code sync-queue.enabled} either. Flipping the
 * runtime flag to {@code enabled=true} must still be enough for a newly sync-admitted invocation
 * to dispatch, without a restart.
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
        InvocationDispatch invocationService() {
            return mock(InvocationDispatch.class);
        }

        @Bean
        FunctionCapacityRegistry functionCapacityRegistry() {
            return new FunctionCapacityRegistry();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestSupport.class)
            .withConfiguration(AutoConfigurations.of(SyncQueueConfiguration.class, SchedulerConfiguration.class))
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
    void enablingSyncQueueAtRuntimeDispatchesThroughTheComposedEngine() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            // A4's successor: the engine's lifecycle adapter exists and is running from module
            // load, not gated on sync-queue.enabled (which is runtime-mutable).
            SchedulerLifecycleAdapter lifecycleAdapter = context.getBean(SchedulerLifecycleAdapter.class);
            assertThat(lifecycleAdapter.isRunning())
                    .as("the composed engine must be running so a runtime activation is "
                            + "drained without a restart")
                    .isTrue();

            InvocationDispatch invocationService = context.getBean(InvocationDispatch.class);
            CountDownLatch dispatched = new CountDownLatch(1);
            doAnswer(invocation -> {
                dispatched.countDown();
                return null;
            }).when(invocationService).dispatch(any(InvocationTask.class));

            MutableSyncQueueConfigSource configSource = context.getBean(MutableSyncQueueConfigSource.class);
            assertThat(configSource.syncQueueEnabled()).isFalse();
            configSource.apply(Map.of(MutableSyncQueueConfigSource.KEY_ENABLED, true));
            assertThat(configSource.syncQueueEnabled()).isTrue();

            // A real queued invocation (as the runtime-activated path would admit one): the
            // running engine must pick it up and dispatch it.
            FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null,
                    1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
            InvocationTask task = new InvocationTask("e1", "fn", spec,
                    new InvocationRequest("one", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
            context.getBean(ExecutionStore.class).put(new ExecutionRecord(task.executionId(), task));
            context.getBean(FunctionRegistrationListener.class).onRegister(spec);

            assertThat(context.getBean(EngineSyncQueueGateway.class).enqueue(task)).isTrue();
            assertThat(dispatched.await(5, TimeUnit.SECONDS))
                    .as("the engine must dispatch a sync-admitted task once the sync queue is "
                            + "enabled at runtime")
                    .isTrue();
        });
    }
}
