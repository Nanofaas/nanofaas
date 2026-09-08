package it.unimib.datai.nanofaas.modules.syncqueue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.ExecutionCompletionHandler;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
import it.unimib.datai.nanofaas.controlplane.service.Metrics;
import it.unimib.datai.nanofaas.modules.syncqueue.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.modules.syncqueue.scheduler.SyncScheduler;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueItem;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueMetrics;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueService;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
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
        InvocationService invocationService() {
            return mock(InvocationService.class);
        }

        @Bean
        FunctionCapacityRegistry functionCapacityRegistry() {
            return new FunctionCapacityRegistry();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestSupport.class, SyncQueueConfiguration.class)
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

            SyncScheduler scheduler = context.getBean(SyncScheduler.class);
        // A refreshed context auto-starts SmartLifecycle beans; asserting that is the point,
        // a "start it if it isn't running" guard would make the next assertion unfalsifiable.
        assertThat(scheduler.isRunning()).isTrue();
            assertThat(scheduler.isRunning()).isTrue();

            MutableSyncQueueConfigSource configSource = context.getBean(MutableSyncQueueConfigSource.class);
            SyncQueueService queue = context.getBean(SyncQueueService.class);
            SyncQueueInvocationEnqueuer enqueuer = context.getBean(SyncQueueInvocationEnqueuer.class);
            ExecutionStore store = context.getBean(ExecutionStore.class);

            Queue<String> dispatched = new ConcurrentLinkedQueue<>();
            InvocationService invocationService = context.getBean(InvocationService.class);
            doAnswer(invocation -> {
                dispatched.add(((InvocationTask) invocation.getArgument(0)).executionId());
                return null;
            }).when(invocationService).dispatch(any(InvocationTask.class));

            FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null,
                    1000, 1, 2, 3, null, ExecutionMode.LOCAL, null, null, null);

            // Task admitted while the queue is enabled, but the function has no capacity
            // yet, so the scheduler cannot dispatch it: it stays queued across the flip.
            InvocationTask admitted = task("admitted-while-enabled", spec);
            store.put(new ExecutionRecord(admitted.executionId(), admitted));
            assertThat(configSource.syncQueueEnabled()).isTrue();
            assertThat(enqueuer.enqueue(admitted)).isTrue();
            assertThat(queue.queuedItems()).isEqualTo(1);

            // Deactivate the queue. NEW invocations now take the non-queue path (the core
            // coordinator reads this flag); the already-admitted task must keep draining.
            configSource.apply(Map.of(MutableSyncQueueConfigSource.KEY_ENABLED, false));
            assertThat(configSource.syncQueueEnabled()).isFalse();

            // Release the queued work by giving the function capacity: it must be drained
            // even though the queue was deactivated before it could be dispatched.
            queue.registerFunction("fn", 2);
            Awaitility.await("admitted work drains after deactivation")
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(dispatched).contains(admitted.executionId()));

            // Re-activation: the flag routes NEW invocations back into the queue and the
            // still-running scheduler dispatches them, again without a restart.
            configSource.apply(Map.of(MutableSyncQueueConfigSource.KEY_ENABLED, true));
            assertThat(configSource.syncQueueEnabled()).isTrue();

            InvocationTask reactivated = task("admitted-after-reactivation", spec);
            store.put(new ExecutionRecord(reactivated.executionId(), reactivated));
            assertThat(enqueuer.enqueue(reactivated)).isTrue();

            Awaitility.await("reactivated queue dispatches new work")
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(dispatched).contains(reactivated.executionId()));
        });
    }

    private static InvocationTask task(String executionId, FunctionSpec spec) {
        return new InvocationTask(executionId, spec.name(), spec,
                new InvocationRequest("payload", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
    }

    /**
     * A4: a retry of work that was admitted while the queue was enabled must keep being
     * drained after a runtime deactivation, not be abandoned. Manual-pump style (like the
     * A3 retry integration tests): the scheduler thread is not started, each attempt is
     * popped from the real queue and dispatched, so the test is deterministic.
     */
    @Test
    void retryOfAdmittedWorkIsNotAbandonedAfterRuntimeDeactivation() {
        ExecutionStore store = new ExecutionStore();
        FunctionCapacityRegistry capacityRegistry = new FunctionCapacityRegistry();
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(30), 2, Duration.ofSeconds(30), 3);
        MutableSyncQueueConfigSource configSource = new MutableSyncQueueConfigSource(props);
        SyncQueueMetrics metrics = new SyncQueueMetrics(new SimpleMeterRegistry());
        SyncQueueService queue = new SyncQueueService(props, store, metrics, configSource, capacityRegistry, null);
        queue.registerFunction("fn", 1);
        SyncQueueInvocationEnqueuer enqueuer = new SyncQueueInvocationEnqueuer(capacityRegistry, null, ignored -> { }, queue);

        AtomicInteger attempts = new AtomicInteger();
        DispatcherRouter router = mock(DispatcherRouter.class);
        when(router.dispatchLocal(any())).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(
                        DispatchResult.warm(InvocationResult.error("ERROR", "attempt 1 failed")));
            }
            return CompletableFuture.completedFuture(DispatchResult.warm(InvocationResult.success("ok")));
        });
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, enqueuer, router, new Metrics(new SimpleMeterRegistry()));

        FunctionSpec spec = spec(1);
        InvocationTask admitted = task("exec-admitted-before-disable", spec);
        ExecutionRecord record = new ExecutionRecord(admitted.executionId(), admitted);
        store.put(record);
        assertThat(enqueuer.enqueue(admitted)).isTrue();

        pollAndDispatch(queue, enqueuer, handler, "fn"); // attempt 1 fails; retry re-queued
        assertThat(record.completion().isDone()).isFalse();
        assertThat(record.state()).isEqualTo(ExecutionState.QUEUED);

        // Deactivate the queue while the retry is queued: the admitted work still drains.
        configSource.apply(Map.of(MutableSyncQueueConfigSource.KEY_ENABLED, false));
        assertThat(queue.enabled()).isFalse();

        pollAndDispatch(queue, enqueuer, handler, "fn"); // attempt 2 succeeds after deactivation

        assertThat(record.completion().isDone()).isTrue();
        assertThat(record.completion().join().success()).isTrue();
        assertThat(record.state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(attempts.get()).isEqualTo(2);
    }

    private static FunctionSpec spec(int maxRetries) {
        return new FunctionSpec("fn", "image", null, Map.of(), null,
                1000, 1, 10, maxRetries, null, ExecutionMode.LOCAL, null, null, null);
    }

    private static void pollAndDispatch(SyncQueueService queue, SyncQueueInvocationEnqueuer enqueuer,
                                        ExecutionCompletionHandler handler, String functionName) {
        assertThat(enqueuer.tryAcquireSlot(functionName)).isTrue();
        SyncQueueItem item = queue.pollReady(Instant.now());
        assertThat(item).isNotNull();
        handler.dispatch(item.task());
    }
}
