package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationDispatch;
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
        QueueManager queueManager = SchedulerLeaseTestSupport.queueManager();
        InvocationDispatch invocationService = mock(InvocationDispatch.class);
        FunctionQueueState hotState = mock(FunctionQueueState.class);
        FunctionQueueState coldState = mock(FunctionQueueState.class);

        InvocationTask hotOne = new InvocationTask("hot-1", "fn", null, null, null, null, java.time.Instant.now(), 1, it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind.SYNC);
        InvocationTask hotTwo = new InvocationTask("hot-2", "fn", null, null, null, null, java.time.Instant.now(), 1, it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind.SYNC);
        InvocationTask hotThree = new InvocationTask("hot-3", "fn", null, null, null, null, java.time.Instant.now(), 1, it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind.SYNC);
        InvocationTask coldOne = new InvocationTask("cold-1", "fn", null, null, null, null, java.time.Instant.now(), 1, it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind.SYNC);

        when(queueManager.get("hot-fn")).thenReturn(hotState);
        when(queueManager.get("cold-fn")).thenReturn(coldState);
        SchedulerLeaseTestSupport.allow(queueManager, hotState, true, true, true, false);
        SchedulerLeaseTestSupport.allow(queueManager, coldState, true, false);
        when(hotState.pollForDispatch()).thenReturn(hotOne, hotTwo, hotThree, null);
        when(coldState.pollForDispatch()).thenReturn(coldOne, (InvocationTask) null);
        when(hotState.queued()).thenReturn(1, 0);
        when(hotState.canDispatch()).thenReturn(true);
        when(coldState.queued()).thenReturn(0);

        List<String> dispatchOrder = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            InvocationTask task = invocation.getArgument(0);
            dispatchOrder.add(task.executionId());
            return null;
        }).when(invocationService).dispatch(org.mockito.ArgumentMatchers.any(InvocationTask.class));

        Scheduler scheduler = new Scheduler(queueManager, invocationService, org.mockito.Mockito.mock(it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle.class));
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
