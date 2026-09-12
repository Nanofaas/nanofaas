package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationQuotaExceededException;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationDispatch;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SchedulerResilienceTest {

    @Test
    void inputCapacityBackpressureRequeuesTaskAndReleasesDispatchLease() {
        QueueManager queueManager = SchedulerLeaseTestSupport.queueManager();
        InvocationDispatch invocationService = mock(InvocationDispatch.class);
        FunctionQueueState state = mock(FunctionQueueState.class);
        InvocationTask task = task("input-blocked", functionSpec("fn", 1, 10));
        when(queueManager.get("fn")).thenReturn(state);
        SchedulerLeaseTestSupport.allow(queueManager, state, true);
        when(state.pollForDispatch()).thenReturn(task);
        when(state.requeueAfterInputBackpressure(any())).thenReturn(true);
        when(state.queued()).thenReturn(0);
        doThrow(new InvocationQuotaExceededException(
                InvocationQuotaExceededException.Resource.INPUT))
                .when(invocationService).dispatch(any(InvocationTask.class));
        Scheduler scheduler = new Scheduler(queueManager, invocationService, org.mockito.Mockito.mock(it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle.class), System::nanoTime, 1);
        scheduler.init();
        scheduler.start();
        try {
            scheduler.signalWork("fn");

            Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    verify(state).requeueAfterInputBackpressure(argThat(requeued -> requeued != null
                            && requeued.executionId().equals(task.executionId())
                            && requeued.dispatchLease() == null)));
        } finally {
            scheduler.stop();
        }

        assertThat(SchedulerLeaseTestSupport.released(queueManager)).isEqualTo(1);
    }

    @Test
    void dispatchExceptionRecordsSlotHoldAndReleasesTheAcquiredState() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueManager queueManager = new QueueManager(registry, new FunctionCapacityRegistry());
        FunctionSpec spec = functionSpec("failed", 1, 10);
        FunctionQueueState state = queueManager.getOrCreate(spec);
        InvocationTask task = task("failed-1", spec);
        var store = new it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore();
        var record = new it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord(task.executionId(), task);
        store.put(record);
        assertThat(queueManager.enqueue(task)).isTrue();
        InvocationDispatch invocationService = mock(InvocationDispatch.class);
        doThrow(new RuntimeException("dispatch failed")).when(invocationService).dispatch(argThat(actual -> actual != null && actual.withDispatchLease(null).equals(task)));

        Scheduler scheduler = new Scheduler(queueManager, invocationService, store);
        scheduler.init();
        scheduler.start();
        try {
            scheduler.signalWork("failed");

            Awaitility.await()
                    .atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> {
                        assertThat(state.inFlight()).isZero();
                        assertThat(record.completion()).isDone();
                        assertThat(store.outcomeOf(task.executionId()).error().code()).isEqualTo("DISPATCH_REJECTED");
                        assertThat(registry.get("function_dispatch_slot_hold_events")
                                .tag("function", "failed")
                                .counter()
                                .count()).isEqualTo(2); // dispatched lease plus the following empty-queue probe
                        assertThat(registry.get("function_dispatch_slot_hold_seconds")
                                .tag("function", "failed")
                                .counter()
                                .count()).isPositive();
                    });
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void dispatchException_doesNotKillSchedulerLoop() {
        QueueManager queueManager = SchedulerLeaseTestSupport.queueManager();
        InvocationDispatch invocationService = mock(InvocationDispatch.class);
        FunctionQueueState state = mock(FunctionQueueState.class);
        InvocationTask task = task("task", functionSpec("testFunc", 1, 10));

        when(queueManager.get("testFunc")).thenReturn(state);
        SchedulerLeaseTestSupport.allow(queueManager, state, true, false); // Process once per signal
        when(state.pollForDispatch()).thenReturn(task);
        doThrow(new RuntimeException("dispatch failed")).when(invocationService).dispatch(argThat(actual -> actual != null && actual.withDispatchLease(null).equals(task)));

        Scheduler scheduler = new Scheduler(queueManager, invocationService, org.mockito.Mockito.mock(it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle.class));
        scheduler.init();
        scheduler.start();
        try {
            // First signal
            scheduler.signalWork("testFunc");
            
            // Verify first dispatch was attempted
            Awaitility.await()
                    .atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> verify(invocationService, atLeastOnce()).dispatch(argThat(actual -> actual != null && actual.withDispatchLease(null).equals(task))));

            // Signal again to prove loop is still alive
            reset(invocationService);
            doNothing().when(invocationService).dispatch(argThat(actual -> actual != null && actual.withDispatchLease(null).equals(task)));
            SchedulerLeaseTestSupport.allow(queueManager, state, true, false);
            when(state.pollForDispatch()).thenReturn(task);
            
            scheduler.signalWork("testFunc");
            
            Awaitility.await()
                    .atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> verify(invocationService, atLeastOnce()).dispatch(argThat(actual -> actual != null && actual.withDispatchLease(null).equals(task))));
        } finally {
            scheduler.stop();
        }

        assertThat(SchedulerLeaseTestSupport.released(queueManager)).isPositive();
    }

    @Test
    void startStopStart_restartsSchedulerWithoutRejectedExecution() {
        QueueManager queueManager = SchedulerLeaseTestSupport.queueManager();
        InvocationDispatch invocationService = mock(InvocationDispatch.class);

        Scheduler scheduler = new Scheduler(queueManager, invocationService, org.mockito.Mockito.mock(it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle.class));
        scheduler.init();
        scheduler.start();
        scheduler.stop();

        try {
            assertThatCode(scheduler::start).doesNotThrowAnyException();
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void scheduler_requeuesFunctionAfterBoundedBatchInsteadOfDrainingWholeBurst() {
        QueueManager queueManager = SchedulerLeaseTestSupport.queueManager();
        InvocationDispatch invocationService = mock(InvocationDispatch.class);
        FunctionQueueState state = mock(FunctionQueueState.class);
        InvocationTask task1 = task("task1", functionSpec("testFunc", 1, 10));
        InvocationTask task2 = task("task2", functionSpec("testFunc", 1, 10));
        InvocationTask task3 = task("task3", functionSpec("testFunc", 1, 10));

        when(queueManager.get("hot")).thenReturn(state);
        SchedulerLeaseTestSupport.allow(queueManager, state, true, true, true, false);
        when(state.pollForDispatch()).thenReturn(task1, task2, task3, null);
        when(state.queued()).thenReturn(1, 0);
        when(state.canDispatch()).thenReturn(true);

        List<InvocationTask> dispatched = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            dispatched.add(invocation.getArgument(0));
            return null;
        }).when(invocationService).dispatch(any(InvocationTask.class));

        Scheduler scheduler = new Scheduler(queueManager, invocationService, org.mockito.Mockito.mock(it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle.class));
        scheduler.init();
        scheduler.start();
        try {
            scheduler.signalWork("hot");

            Awaitility.await()
                    .atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(dispatched.stream().map(t -> t.withDispatchLease(null)).toList()).containsExactly(task1, task2, task3));
        } finally {
            scheduler.stop();
        }

        verify(invocationService, times(3)).dispatch(any(InvocationTask.class));
        verify(state, atLeastOnce()).queued();
    }

    @Test
    void blockedBacklog_doesNotSpinUntilSlotIsReleased() {
        CountingQueueManager queueManager = new CountingQueueManager("blocked");
        InvocationDispatch invocationService = mock(InvocationDispatch.class);
        FunctionSpec spec = functionSpec("blocked", 1, 10);
        FunctionQueueState state = queueManager.getOrCreate(spec);
        InvocationTask task1 = task("blocked-1", spec);
        InvocationTask task2 = task("blocked-2", spec);

        assertThat(queueManager.enqueue(task1)).isTrue();
        assertThat(queueManager.enqueue(task2)).isTrue();

        Scheduler scheduler = new Scheduler(queueManager, invocationService, org.mockito.Mockito.mock(it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle.class));
        scheduler.init();
        scheduler.start();
        try {
            scheduler.signalWork("blocked");

            Awaitility.await()
                    .atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> verify(invocationService).dispatch(argThat(actual -> actual != null && actual.withDispatchLease(null).equals(task1))));

            Awaitility.await()
                    .during(Duration.ofMillis(250))
                    .atMost(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        verify(invocationService, times(1)).dispatch(any(InvocationTask.class));
                        verify(invocationService, never()).dispatch(argThat(actual -> actual != null && actual.withDispatchLease(null).equals(task2)));
                        assertThat(state.queued()).isEqualTo(1);
                        assertThat(state.inFlight()).isEqualTo(1);
                        assertThat(queueManager.getCalls())
                                .as("scheduler should not revisit a queued function with no free dispatch slot")
                                .isEqualTo(1);
                    });

            org.mockito.ArgumentCaptor<InvocationTask> captured = org.mockito.ArgumentCaptor.forClass(InvocationTask.class);
            verify(invocationService).dispatch(captured.capture());
            captured.getValue().dispatchLease().release();

            Awaitility.await()
                    .atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> verify(invocationService).dispatch(argThat(actual -> actual != null && actual.withDispatchLease(null).equals(task2))));
        } finally {
            scheduler.stop();
        }

        verify(invocationService, times(2)).dispatch(any(InvocationTask.class));
    }

    private FunctionSpec functionSpec(String functionName, int concurrency, int queueSize) {
        return new FunctionSpec(
                functionName,
                "image",
                null,
                Map.of(),
                null,
                1000,
                concurrency,
                queueSize,
                3,
                null,
                ExecutionMode.LOCAL,
                null,
                null,
                null
        );
    }

    private InvocationTask task(String executionId, FunctionSpec spec) {
        return new InvocationTask(
                executionId,
                spec.name(),
                spec,
                new InvocationRequest("payload-" + executionId, Map.of()),
                null,
                null,
                Instant.now(),
                1
        ,
        InvocationKind.SYNC
    );
    }

    private static class CountingQueueManager extends QueueManager {
        private final String countedFunction;
        private final AtomicInteger getCalls = new AtomicInteger();

        CountingQueueManager(String countedFunction) {
            super(new SimpleMeterRegistry(), new FunctionCapacityRegistry());
            this.countedFunction = countedFunction;
        }

        @Override
        public FunctionQueueState get(String functionName) {
            if (countedFunction.equals(functionName)) {
                getCalls.incrementAndGet();
            }
            return super.get(functionName);
        }

        int getCalls() {
            return getCalls.get();
        }
    }
}
