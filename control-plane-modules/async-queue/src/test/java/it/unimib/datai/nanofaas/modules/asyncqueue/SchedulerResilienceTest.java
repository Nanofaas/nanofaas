package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
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
        when(state.tryAcquireSlot()).thenReturn(true, true, false, true, false);
        when(state.poll()).thenReturn(task1, task2, task3, null);
        when(state.queued()).thenReturn(1, 1, 0);

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
}
