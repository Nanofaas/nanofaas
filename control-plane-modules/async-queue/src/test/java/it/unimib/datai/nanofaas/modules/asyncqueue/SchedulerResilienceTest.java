package it.unimib.datai.nanofaas.modules.asyncqueue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
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
    void dispatchException_doesNotKillSchedulerLoop() {
        QueueManager queueManager = mock(QueueManager.class);
        InvocationService invocationService = mock(InvocationService.class);
        FunctionQueueState state = mock(FunctionQueueState.class);
        InvocationTask task = mock(InvocationTask.class);

        when(queueManager.get("testFunc")).thenReturn(state);
        when(state.tryAcquireSlot()).thenReturn(true).thenReturn(false); // Process once per signal
        when(state.poll()).thenReturn(task);
        doThrow(new RuntimeException("dispatch failed")).when(invocationService).dispatch(task);

        Scheduler scheduler = new Scheduler(queueManager, invocationService);
        scheduler.init();
        scheduler.start();
        try {
            // First signal
            scheduler.signalWork("testFunc");
            
            // Verify first dispatch was attempted
            Awaitility.await()
                    .atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> verify(invocationService, atLeastOnce()).dispatch(task));

            // Signal again to prove loop is still alive
            reset(invocationService);
            doNothing().when(invocationService).dispatch(task);
            when(state.tryAcquireSlot()).thenReturn(true).thenReturn(false);
            when(state.poll()).thenReturn(task);
            
            scheduler.signalWork("testFunc");
            
            Awaitility.await()
                    .atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> verify(invocationService, atLeastOnce()).dispatch(task));
        } finally {
            scheduler.stop();
        }

        verify(state, atLeastOnce()).releaseSlot();
    }

    @Test
    void startStopStart_restartsSchedulerWithoutRejectedExecution() {
        QueueManager queueManager = mock(QueueManager.class);
        InvocationService invocationService = mock(InvocationService.class);

        Scheduler scheduler = new Scheduler(queueManager, invocationService);
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
        QueueManager queueManager = mock(QueueManager.class);
        InvocationService invocationService = mock(InvocationService.class);
        FunctionQueueState state = mock(FunctionQueueState.class);
        InvocationTask task1 = mock(InvocationTask.class);
        InvocationTask task2 = mock(InvocationTask.class);
        InvocationTask task3 = mock(InvocationTask.class);

        when(queueManager.get("hot")).thenReturn(state);
        when(state.tryAcquireSlot()).thenReturn(true, true, true, false);
        when(state.poll()).thenReturn(task1, task2, task3, null);
        when(state.queued()).thenReturn(1, 0);

        List<InvocationTask> dispatched = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            dispatched.add(invocation.getArgument(0));
            return null;
        }).when(invocationService).dispatch(any(InvocationTask.class));

        Scheduler scheduler = new Scheduler(queueManager, invocationService);
        scheduler.init();
        scheduler.start();
        try {
            scheduler.signalWork("hot");

            Awaitility.await()
                    .atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(dispatched).containsExactly(task1, task2, task3));
        } finally {
            scheduler.stop();
        }

        verify(invocationService, times(3)).dispatch(any(InvocationTask.class));
        verify(state, atLeastOnce()).queued();
    }

    @Test
    void blockedBacklog_doesNotSpinUntilSlotIsReleased() {
        CountingQueueManager queueManager = new CountingQueueManager("blocked");
        InvocationService invocationService = mock(InvocationService.class);
        FunctionSpec spec = functionSpec("blocked", 1, 10);
        FunctionQueueState state = queueManager.getOrCreate(spec);
        InvocationTask task1 = task("blocked-1", spec);
        InvocationTask task2 = task("blocked-2", spec);

        assertThat(queueManager.enqueue(task1)).isTrue();
        assertThat(queueManager.enqueue(task2)).isTrue();

        Scheduler scheduler = new Scheduler(queueManager, invocationService);
        scheduler.init();
        scheduler.start();
        try {
            scheduler.signalWork("blocked");

            Awaitility.await()
                    .atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> verify(invocationService).dispatch(task1));

            Awaitility.await()
                    .during(Duration.ofMillis(250))
                    .atMost(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        verify(invocationService, times(1)).dispatch(any(InvocationTask.class));
                        verify(invocationService, never()).dispatch(task2);
                        assertThat(state.queued()).isEqualTo(1);
                        assertThat(state.inFlight()).isEqualTo(1);
                        assertThat(queueManager.getCalls())
                                .as("scheduler should not spin on a queued function with no free dispatch slot")
                                .isLessThanOrEqualTo(3);
                    });

            queueManager.releaseSlot("blocked");

            Awaitility.await()
                    .atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> verify(invocationService).dispatch(task2));
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
        );
    }

    private static class CountingQueueManager extends QueueManager {
        private final String countedFunction;
        private final AtomicInteger getCalls = new AtomicInteger();

        CountingQueueManager(String countedFunction) {
            super(new SimpleMeterRegistry());
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
