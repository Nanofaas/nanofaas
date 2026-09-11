package it.unimib.datai.nanofaas.modules.syncqueue.scheduler;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.QueuedDispatchCapacity;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationDispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SyncSchedulerLifecycleTest {

    @Test
    void startAndStop_toggleRunningState() {
        QueuedDispatchCapacity enqueuer = mock(QueuedDispatchCapacity.class);
        SyncQueueService queue = mock(SyncQueueService.class);
        @SuppressWarnings("unchecked")
        InvocationDispatch dispatch = mock(InvocationDispatch.class);

        when(queue.peekReady(any(Instant.class))).thenReturn(null);

        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, dispatch, org.mockito.Mockito.mock(it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle.class));
        assertThat(scheduler.isRunning()).isFalse();

        scheduler.start();
        assertThat(scheduler.isRunning()).isTrue();

        scheduler.stop();
        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    void stopWithoutStart_keepsSchedulerStopped() {
        QueuedDispatchCapacity enqueuer = mock(QueuedDispatchCapacity.class);
        SyncQueueService queue = mock(SyncQueueService.class);
        @SuppressWarnings("unchecked")
        InvocationDispatch dispatch = mock(InvocationDispatch.class);

        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, dispatch, org.mockito.Mockito.mock(it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle.class));

        scheduler.stop();

        assertThat(scheduler.isRunning()).isFalse();
        verifyNoInteractions(queue, enqueuer, dispatch);
    }

    @Test
    void startStopStart_restartsSchedulerWithoutRejectedExecution() {
        QueuedDispatchCapacity enqueuer = mock(QueuedDispatchCapacity.class);
        SyncQueueService queue = mock(SyncQueueService.class);
        @SuppressWarnings("unchecked")
        InvocationDispatch dispatch = mock(InvocationDispatch.class);

        when(queue.peekReady(any(Instant.class))).thenReturn(null);

        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, dispatch, org.mockito.Mockito.mock(it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle.class));
        scheduler.start();
        scheduler.stop();

        try {
            assertThatCode(scheduler::start).doesNotThrowAnyException();
            assertThat(scheduler.isRunning()).isTrue();
        } finally {
            scheduler.stop();
        }
    }
}
