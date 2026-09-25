package it.unimib.datai.nanofaas.modules.syncqueue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.EngineSyncQueueGateway;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectedException;
import it.unimib.datai.nanofaas.controlplane.service.ExecutionCompletionHandler;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationDispatch;
import it.unimib.datai.nanofaas.controlplane.service.Metrics;
import it.unimib.datai.nanofaas.controlplane.service.SchedulerConfiguration;
import it.unimib.datai.nanofaas.controlplane.service.SchedulerLifecycleAdapter;
import it.unimib.datai.nanofaas.execution.SchedulerEngine;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * A4 lifecycle of the sync queue in a real Spring context: the runtime flag decides the
 * path of NEW invocations, while work already admitted before a deactivation keeps
 * draining (and its retries are never abandoned). The scheduler is created from module
 * load, so a runtime activation needs no restart.
 *
 * <p>Determinism: the draining worker runs on its own thread but is woken by the queue's
 * work signal, so no fragile sleeps are needed. A queued task whose function has no
 * capacity yet cannot be dispatched - that is what lets the test hold a task across the
 * enabled=true -&gt; false transition and only then release it.
 */
class SyncQueueRuntimeLifecycleTest {

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

        /**
         * A spy over the real registry, so
         * {@link #removalSettlesSyncAdmissionEvenAfterTheGatewayReadsTheGeneration} can complete a
         * real removal while the gateway is reading the generation. Unstubbed calls delegate to
         * the real object.
         */
        @Bean
        FunctionCapacityRegistry functionCapacityRegistry() {
            return spy(new FunctionCapacityRegistry());
        }
    }

    /** The function every lifecycle test here registers through {@link #registerEcho}. */
    private static final String PROBE_FUNCTION = "echo";
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestSupport.class)
            .withConfiguration(AutoConfigurations.of(SyncQueueConfiguration.class, SchedulerConfiguration.class))
            .withPropertyValues(
                    "sync-queue.enabled=true",
                    "sync-queue.admission-enabled=false",
                    "sync-queue.max-depth=10",
                    "sync-queue.max-estimated-wait=1s",
                    "sync-queue.max-queue-wait=5s",
                    "sync-queue.retry-after-seconds=1",
                    "sync-queue.throughput-window=1s",
                    "sync-queue.per-function-min-samples=1");

    @Test
    void disablingAfterAdmissionDrainsQueuedWorkAndReactivationDispatchesAgain() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            // A4's successor: the engine's lifecycle adapter, not a per-module scheduler, is
            // what stays running regardless of sync-queue.enabled.
            SchedulerLifecycleAdapter lifecycleAdapter = context.getBean(SchedulerLifecycleAdapter.class);
            assertThat(lifecycleAdapter.isRunning()).isTrue();

            MutableSyncQueueConfigSource configSource = context.getBean(MutableSyncQueueConfigSource.class);
            EngineSyncQueueGateway gateway = context.getBean(EngineSyncQueueGateway.class);
            ExecutionStore store = context.getBean(ExecutionStore.class);
            FunctionCapacityRegistry capacityRegistry = context.getBean(FunctionCapacityRegistry.class);

            Queue<String> dispatched = new ConcurrentLinkedQueue<>();
            InvocationDispatch invocationService = context.getBean(InvocationDispatch.class);
            doAnswer(invocation -> {
                InvocationTask dispatchedTask = invocation.getArgument(0);
                dispatched.add(dispatchedTask.executionId());
                // The mock stands in for the whole execution lifecycle, which is what would
                // normally release the dispatch lease on completion; releasing it here keeps
                // the function's one slot usable for the next task in this test, exactly as a
                // real (fast) completion would.
                dispatchedTask.dispatchLease().release();
                return null;
            }).when(invocationService).dispatch(any(InvocationTask.class));

            FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null,
                    1000, 1, 2, 3, null, ExecutionMode.LOCAL, null, null, null);
            // The function must be capacity-registered before it can be admitted at all (the
            // engine's ticket carries a FunctionGeneration) — register it up front, then hold
            // its one slot so the engine cannot dispatch the first task yet.
            context.getBean("schedulerCapacityGenerationListener", FunctionRegistrationListener.class).onRegister(spec);
            var heldLease = capacityRegistry.tryAcquireLease("fn", 1);
            assertThat(heldLease).isNotNull();

            // Task admitted while the queue is enabled, but the function's one slot is held,
            // so the engine cannot dispatch it: it stays queued across the flip.
            InvocationTask admitted = task("admitted-while-enabled", spec);
            store.put(new ExecutionRecord(admitted.executionId(), admitted));
            assertThat(configSource.syncQueueEnabled()).isTrue();
            assertThat(gateway.enqueue(admitted)).isTrue();

            // Deactivate the queue. NEW invocations now take the non-queue path (the core
            // coordinator reads this flag); the already-admitted task must keep draining.
            configSource.apply(Map.of(MutableSyncQueueConfigSource.KEY_ENABLED, false));
            assertThat(configSource.syncQueueEnabled()).isFalse();

            // Release the held slot: the engine must drain the admitted task even though the
            // queue was deactivated before it could be dispatched.
            heldLease.release();
            Awaitility.await("admitted work drains after deactivation")
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(dispatched).contains(admitted.executionId()));

            // Re-activation: the flag routes NEW invocations back into the queue and the
            // still-running engine dispatches them, again without a restart.
            configSource.apply(Map.of(MutableSyncQueueConfigSource.KEY_ENABLED, true));
            assertThat(configSource.syncQueueEnabled()).isTrue();

            InvocationTask reactivated = task("admitted-after-reactivation", spec);
            store.put(new ExecutionRecord(reactivated.executionId(), reactivated));
            assertThat(gateway.enqueue(reactivated)).isTrue();

            Awaitility.await("reactivated queue dispatches new work")
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(dispatched).contains(reactivated.executionId()));
        });
    }

    /**
     * The observable contract of the live listener: a removed function admits nothing, and a
     * re-registered one admits again.
     */
    @Test
    void aRemovalRejectsSyncAdmissionAndReRegistrationAcceptsItAgain() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            FunctionRegistrationListener listener = context.getBean(
                    "schedulerCapacityGenerationListener", FunctionRegistrationListener.class);
            EngineSyncQueueGateway gateway = context.getBean(EngineSyncQueueGateway.class);
            ExecutionStore store = context.getBean(ExecutionStore.class);
            FunctionSpec spec = new FunctionSpec("echo", "image", null, Map.of(), null,
                    1000, 1, 10, 3, null, ExecutionMode.LOCAL, null, null, null);

            listener.onRegister(spec);
            InvocationTask admitted = task("admitted-before-removal", spec);
            store.put(new ExecutionRecord(admitted.executionId(), admitted));
            assertThatCode(() -> gateway.enqueueOrThrow(admitted)).doesNotThrowAnyException();

            listener.onRemove(spec.name());
            InvocationTask duringRemoval = task("admitted-during-removal", spec);
            store.put(new ExecutionRecord(duringRemoval.executionId(), duringRemoval));
            assertThatThrownBy(() -> gateway.enqueueOrThrow(duringRemoval))
                    .isInstanceOf(SyncQueueRejectedException.class);

            listener.onRegister(spec);
            InvocationTask afterReRegistration = task("admitted-after-reregistration", spec);
            store.put(new ExecutionRecord(afterReRegistration.executionId(), afterReRegistration));
            assertThatCode(() -> gateway.enqueueOrThrow(afterReRegistration))
                    .doesNotThrowAnyException();
        });
    }

    @ParameterizedTest(name = "removal during generation read = {0}")
    @ValueSource(booleans = {false, true})
    void removalSettlesSyncAdmissionEvenAfterTheGatewayReadsTheGeneration(boolean duringGenerationRead) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            // Hold dispatch and expiry while arranging the admission/removal ordering.
            context.getBean(SchedulerLifecycleAdapter.class).stop();
            FunctionRegistrationListener listener = context.getBean(
                    "schedulerCapacityGenerationListener", FunctionRegistrationListener.class);
            EngineSyncQueueGateway gateway = context.getBean(EngineSyncQueueGateway.class);
            FunctionCapacityRegistry capacity = context.getBean(FunctionCapacityRegistry.class);
            SchedulerEngine engine = context.getBean(SchedulerEngine.class);
            ExecutionStore store = context.getBean(ExecutionStore.class);
            FunctionSpec spec = registerEcho(context);
            FunctionRegistrationListener metricsListener = context.getBean(
                    "syncQueueMetricsLifecycleListener", FunctionRegistrationListener.class);
            metricsListener.onRegister(spec);
            InvocationTask task = task("admission-racing-removal", spec);
            ExecutionRecord executionRecord = new ExecutionRecord(task.executionId(), task);
            store.put(executionRecord);

            AtomicBoolean armed = new AtomicBoolean(duringGenerationRead);
            AtomicBoolean removalCompleted = new AtomicBoolean();
            doAnswer(invocation -> {
                FunctionGeneration observed = (FunctionGeneration) invocation.callRealMethod();
                if (armed.compareAndSet(true, false)) {
                    assertThat(observed).isNotNull();
                    // Finish the real removal drain, then return the generation captured before it.
                    listener.onRemove(spec.name());
                    metricsListener.onRemove(spec.name());
                    removalCompleted.set(true);
                }
                return observed;
            }).when(capacity).activeGeneration(spec.name());

            assertThat(gateway.enqueue(task)).isEqualTo(!duringGenerationRead);
            if (!duringGenerationRead) {
                // Control: the same lifecycle must settle a ticket admitted before removal.
                listener.onRemove(spec.name());
                metricsListener.onRemove(spec.name());
                removalCompleted.set(true);
            }

            assertThat(removalCompleted).isTrue();
            assertThat(capacity.activeGeneration(spec.name())).isNull();
            assertThat(engine.reservedCount(spec.name())).isZero();
            assertThat(context.getBean(MeterRegistry.class).get("sync_queue_depth")
                    .tag("function", "").gauge().value()).isZero();
            if (duringGenerationRead) {
                // The bare gateway does not own completion of a ticket it refused.
                assertThat(executionRecord.completion().isDone()).isFalse();
            } else {
                assertThat(executionRecord.state()).isEqualTo(ExecutionState.ERROR);
                assertThat(executionRecord.completion().isDone()).isTrue();
            }
        });
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"FUNCTION_QUEUE", "SYNC_QUEUE"})
    void onlyTheSyncAdmissionProfileFeedsTheEstimatorAfterRuntimeDisable(String profile) {
        runner.withPropertyValues("nanofaas.admission.profile=" + profile).run(context -> {
            context.getBean(SchedulerLifecycleAdapter.class).stop();
            FunctionSpec spec = registerEcho(context);
            context.getBean("syncQueueMetricsLifecycleListener", FunctionRegistrationListener.class)
                    .onRegister(spec);
            var enqueuer = context.getBean(
                    it.unimib.datai.nanofaas.controlplane.service.EngineInvocationEnqueuer.class);
            var estimator = context.getBean(
                    it.unimib.datai.nanofaas.execution.admission.WaitEstimator.class);
            InvocationTask queued = task("profile-dispatch", spec);
            context.getBean(ExecutionStore.class).put(new ExecutionRecord(queued.executionId(), queued));
            doAnswer(invocation -> {
                InvocationTask dispatched = invocation.getArgument(0);
                dispatched.dispatchLease().release();
                return null;
            }).when(context.getBean(InvocationDispatch.class)).dispatch(any(InvocationTask.class));
            assertThat(enqueuer.enqueue(queued)).isTrue();
            context.getBean(MutableSyncQueueConfigSource.class)
                    .apply(Map.of(MutableSyncQueueConfigSource.KEY_ENABLED, false));
            context.getBean(SchedulerEngine.class).tick();
            assertThat(context.getBean(SchedulerEngine.class).reservedCount(spec.name())).isZero();
            double wait = estimator.estimateWaitSeconds(spec.name(), 1, Instant.now());
            assertThat(Double.isFinite(wait)).isEqualTo(profile.equals("SYNC_QUEUE"));
            assertThat(context.getBean(MeterRegistry.class).get("sync_queue_depth")
                    .tag("function", "").gauge().value()).isZero();
        });
    }

    private static FunctionSpec registerEcho(org.springframework.context.ApplicationContext context) {
        FunctionRegistrationListener listener = context.getBean(
                "schedulerCapacityGenerationListener", FunctionRegistrationListener.class);
        FunctionSpec spec = new FunctionSpec(PROBE_FUNCTION, "image", null, Map.of(), null,
                1000, 1, 10, 3, null, ExecutionMode.LOCAL, null, null, null);
        listener.onRegister(spec);
        return spec;
    }

    private static InvocationTask task(String executionId, FunctionSpec spec) {
        return new InvocationTask(executionId, spec.name(), spec,
                new InvocationRequest("payload", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
    }

    /**
     * A4: a retry of work that was admitted while the queue was enabled must keep being
     * drained after a runtime deactivation, not be abandoned. Runs on the real engine: the
     * first attempt disables admission before the real completion handler queues its retry.
     */
    @Test
    void retryOfAdmittedWorkIsNotAbandonedAfterRuntimeDeactivation() {
        runner.withPropertyValues("nanofaas.admission.profile=SYNC_QUEUE").run(context -> {
            context.getBean(SchedulerLifecycleAdapter.class).stop();
            FunctionSpec spec = spec(1);
            context.getBean("schedulerCapacityGenerationListener", FunctionRegistrationListener.class)
                    .onRegister(spec);
            context.getBean("syncQueueMetricsLifecycleListener", FunctionRegistrationListener.class)
                    .onRegister(spec);
            var engine = context.getBean(SchedulerEngine.class);
            var enqueuer = context.getBean(
                    it.unimib.datai.nanofaas.controlplane.service.EngineInvocationEnqueuer.class);
            var config = context.getBean(MutableSyncQueueConfigSource.class);
            var store = context.getBean(ExecutionStore.class);
            var attempts = new AtomicInteger();
            DispatcherRouter router = mock(DispatcherRouter.class);
            when(router.dispatchLocal(any())).thenAnswer(invocation -> {
                if (attempts.incrementAndGet() == 1) {
                    config.apply(Map.of(MutableSyncQueueConfigSource.KEY_ENABLED, false));
                    return CompletableFuture.completedFuture(
                            DispatchResult.warm(InvocationResult.error("ERROR", "attempt 1 failed")));
                }
                return CompletableFuture.completedFuture(DispatchResult.warm(InvocationResult.success("ok")));
            });
            var handler = new ExecutionCompletionHandler(store, enqueuer, router,
                    new Metrics(context.getBean(MeterRegistry.class)));
            doAnswer(invocation -> {
                handler.dispatch(invocation.getArgument(0));
                return null;
            }).when(context.getBean(InvocationDispatch.class)).dispatch(any(InvocationTask.class));
            InvocationTask admitted = task("retry-after-disable", spec);
            ExecutionRecord executionRecord = new ExecutionRecord(admitted.executionId(), admitted);
            store.put(executionRecord);
            assertThat(enqueuer.enqueue(admitted)).isTrue();
            for (int i = 0; i < 3 && !executionRecord.completion().isDone(); i++) {
                engine.tick();
            }
            assertThat(executionRecord.completion().isDone()).isTrue();
            assertThat(executionRecord.completion().join().success()).isTrue();
            assertThat(executionRecord.state()).isEqualTo(ExecutionState.SUCCESS);
            assertThat(attempts.get()).isEqualTo(2);
            assertThat(engine.reservedCount(spec.name())).isZero();
            // Both dispatches fed the estimator, including the retry queued after deactivation:
            // attribution follows the immutable SYNC_QUEUE profile, not the runtime flag.
            assertThat(context.getBean(
                    it.unimib.datai.nanofaas.execution.admission.WaitEstimator.class)
                    .retentionSnapshot().globalSamples()).isEqualTo(2);
        });
    }

    private static FunctionSpec spec(int maxRetries) {
        return new FunctionSpec("fn", "image", null, Map.of(), null,
                1000, 1, 10, maxRetries, null, ExecutionMode.LOCAL, null, null, null);
    }
}
