package it.unimib.datai.nanofaas.controlplane.scheduler;

import it.unimib.datai.nanofaas.controlplane.queue.FunctionQueueState;
import it.unimib.datai.nanofaas.controlplane.queue.QueueManager;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SchedulerResilienceTest {

    @Test
    void dispatchException_doesNotKillSchedulerLoop() {
        QueueManager queueManager = mock(QueueManager.class);
        InvocationService invocationService = mock(InvocationService.class);
        FunctionQueueState state = mock(FunctionQueueState.class);
        InvocationTask task = mock(InvocationTask.class);

        when(state.tryAcquireSlot()).thenReturn(true);
        when(state.poll()).thenReturn(task).thenReturn((InvocationTask) null);
        doThrow(new RuntimeException("dispatch failed")).when(invocationService).dispatch(task);

        AtomicInteger iterations = new AtomicInteger();
        doAnswer(invocation -> {
            iterations.incrementAndGet();
            @SuppressWarnings("unchecked")
            Consumer<FunctionQueueState> action = invocation.getArgument(0);
            action.accept(state);
            return null;
        }).when(queueManager).forEachQueue(any());

        Scheduler scheduler = new Scheduler(queueManager, invocationService);
        scheduler.start();
        try {
            Awaitility.await()
                    .atMost(Duration.ofSeconds(1))
                    .untilAsserted(() -> assertThat(iterations.get()).isGreaterThan(1));
        } finally {
            scheduler.stop();
        }

        verify(state, atLeastOnce()).releaseSlot();
    }
}
