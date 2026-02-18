package it.unimib.datai.nanofaas.controlplane.scheduler;

import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SyncSchedulerLifecycleTest {

    @Test
    void startAndStop_toggleRunningState() {
        InvocationEnqueuer enqueuer = mock(InvocationEnqueuer.class);
        SyncQueueService queue = mock(SyncQueueService.class);
        @SuppressWarnings("unchecked")
        Consumer<InvocationTask> dispatch = mock(Consumer.class);

        when(queue.peekReady(any(Instant.class))).thenReturn(null);

        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, dispatch);
        assertThat(scheduler.isRunning()).isFalse();

        scheduler.start();
        assertThat(scheduler.isRunning()).isTrue();

        scheduler.stop();
        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    void stopWithoutStart_keepsSchedulerStopped() {
        InvocationEnqueuer enqueuer = mock(InvocationEnqueuer.class);
        SyncQueueService queue = mock(SyncQueueService.class);
        @SuppressWarnings("unchecked")
        Consumer<InvocationTask> dispatch = mock(Consumer.class);

        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, dispatch);

        scheduler.stop();

        assertThat(scheduler.isRunning()).isFalse();
        verifyNoInteractions(queue, enqueuer, dispatch);
    }
}
