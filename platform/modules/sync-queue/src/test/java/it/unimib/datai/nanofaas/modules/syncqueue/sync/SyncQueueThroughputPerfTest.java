package it.unimib.datai.nanofaas.modules.syncqueue.sync;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.modules.syncqueue.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.modules.syncqueue.scheduler.SyncScheduler;
import it.unimib.datai.nanofaas.controlplane.scheduler.QueuedDispatchCapacity;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationDispatch;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncQueueThroughputPerfTest {

    private final ExecutionStore executionStore = new ExecutionStore();

    @Test
    void syncQueue_readyWorkBehindBlockedHead_stillMakesProgress() {
        QueuedDispatchCapacity enqueuer = it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.enqueuer();
        InvocationDispatch invocationService = mock(InvocationDispatch.class);
        when(enqueuer.hasAvailableSlot("blocked-fn")).thenReturn(false);
        when(enqueuer.hasAvailableSlot("ready-fn")).thenReturn(true);
        it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.allow(enqueuer, "ready-fn", true);

        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        SyncQueueService queue = new SyncQueueService(
                props,
                executionStore,
                new SyncQueueMetrics(new SimpleMeterRegistry()),
                SyncQueueConfigSource.fixed(props.runtimeDefaults()),
                new FunctionCapacityRegistry(),
                null
        );

        InvocationTask blocked = task("blocked-exec", "blocked-fn");
        InvocationTask ready = task("ready-exec", "ready-fn");
        executionStore.put(new ExecutionRecord(blocked.executionId(), blocked));
        executionStore.put(new ExecutionRecord(ready.executionId(), ready));
        AtomicReference<String> dispatched = new AtomicReference<>();
        doAnswer(invocation -> {
            InvocationTask dispatchedTask = invocation.getArgument(0);
            dispatched.set(dispatchedTask.executionId());
            return null;
        }).when(invocationService).dispatch(org.mockito.ArgumentMatchers.any());

        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, invocationService, org.mockito.Mockito.mock(it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle.class));
        scheduler.start();
        try {
            queue.enqueueOrThrow(blocked);
            queue.enqueueOrThrow(ready);

            Awaitility.await()
                    .atMost(Duration.ofSeconds(1))
                    .untilAsserted(() -> assertThat(dispatched.get())
                            .as("a blocked head item should not prevent ready work for a different function from dispatching")
                            .isEqualTo("ready-exec"));
        } finally {
            scheduler.stop();
        }

        verify(invocationService).dispatch(org.mockito.ArgumentMatchers.argThat(actual ->
                actual.withDispatchLease(null).equals(ready) && actual.dispatchLease() != null));
    }

    private static InvocationTask task(String executionId, String functionName) {
        return new InvocationTask(
                executionId,
                functionName,
                new FunctionSpec(
                        functionName,
                        "example/image:latest",
                        null,
                        Map.of(),
                        null,
                        1_000,
                        1,
                        8,
                        3,
                        null,
                        ExecutionMode.LOCAL,
                        null,
                        null,
                        null
                ),
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                Instant.now(),
                1
        ,
        InvocationKind.SYNC
    );
    }
}
