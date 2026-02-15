package it.unimib.datai.nanofaas.controlplane.scheduler;

import it.unimib.datai.nanofaas.controlplane.queue.QueueManager;
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
        QueueManager queueManager = mock(QueueManager.class);
        SyncQueueService queue = mock(SyncQueueService.class);
        @SuppressWarnings("unchecked")
        Consumer<InvocationTask> dispatch = mock(Consumer.class);

        when(queue.peekReady(any(Instant.class))).thenReturn(null);

        SyncScheduler scheduler = new SyncScheduler(queueManager, queue, dispatch);
        assertThat(scheduler.isRunning()).isFalse();

        scheduler.start();
        assertThat(scheduler.isRunning()).isTrue();

        scheduler.stop();
        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    void stopWithoutStart_keepsSchedulerStopped() {
        QueueManager queueManager = mock(QueueManager.class);
        SyncQueueService queue = mock(SyncQueueService.class);
        @SuppressWarnings("unchecked")
        Consumer<InvocationTask> dispatch = mock(Consumer.class);

        SyncScheduler scheduler = new SyncScheduler(queueManager, queue, dispatch);

        scheduler.stop();

        assertThat(scheduler.isRunning()).isFalse();
        verifyNoInteractions(queue, queueManager, dispatch);
    }
}
