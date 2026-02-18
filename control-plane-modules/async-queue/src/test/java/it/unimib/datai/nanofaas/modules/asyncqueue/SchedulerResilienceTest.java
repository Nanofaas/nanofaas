package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
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
}
