package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AsyncSchedulerFairnessPerfTest {

    @Test
    void asyncScheduler_hotFunctionDoesNotStarveSecondFunction() {
        QueueManager queueManager = mock(QueueManager.class);
        InvocationService invocationService = mock(InvocationService.class);
        FunctionQueueState hotState = mock(FunctionQueueState.class);
        FunctionQueueState coldState = mock(FunctionQueueState.class);

        InvocationTask hotOne = mock(InvocationTask.class);
        InvocationTask hotTwo = mock(InvocationTask.class);
        InvocationTask hotThree = mock(InvocationTask.class);
        InvocationTask coldOne = mock(InvocationTask.class);
        when(hotOne.executionId()).thenReturn("hot-1");
        when(hotTwo.executionId()).thenReturn("hot-2");
        when(hotThree.executionId()).thenReturn("hot-3");
        when(coldOne.executionId()).thenReturn("cold-1");

        when(queueManager.get("hot-fn")).thenReturn(hotState);
        when(queueManager.get("cold-fn")).thenReturn(coldState);
        when(hotState.tryAcquireSlot()).thenReturn(true, true, true, false);
        when(coldState.tryAcquireSlot()).thenReturn(true, false);
        when(hotState.poll()).thenReturn(hotOne, hotTwo, hotThree, null);
        when(coldState.poll()).thenReturn(coldOne, (InvocationTask) null);

        List<String> dispatchOrder = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            InvocationTask task = invocation.getArgument(0);
            dispatchOrder.add(task.executionId());
            return null;
        }).when(invocationService).dispatch(org.mockito.ArgumentMatchers.any(InvocationTask.class));

        Scheduler scheduler = new Scheduler(queueManager, invocationService);
        scheduler.init();
        scheduler.start();
        try {
            scheduler.signalWork("hot-fn");
            scheduler.signalWork("cold-fn");

            Awaitility.await()
                    .atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(dispatchOrder).hasSize(4));

            assertThat(dispatchOrder.indexOf("cold-1"))
                    .as("cold work should dispatch before the hot function drains its entire burst")
                    .isLessThan(dispatchOrder.indexOf("hot-3"));
        } finally {
            scheduler.stop();
        }
    }
}
