package it.unimib.datai.nanofaas.controlplane.scheduler;

import it.unimib.datai.nanofaas.controlplane.queue.QueueManager;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueItem;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SyncSchedulerBranchTest {

    @Test
    void tickOnce_whenNoReadyItem_doesNotDispatch() {
        QueueManager queueManager = mock(QueueManager.class);
        SyncQueueService queue = mock(SyncQueueService.class);
        @SuppressWarnings("unchecked")
        Consumer<InvocationTask> dispatch = mock(Consumer.class);

        when(queue.peekReady(any(Instant.class))).thenReturn(null);

        SyncScheduler scheduler = new SyncScheduler(queueManager, queue, dispatch);
        scheduler.tickOnce();

        verify(queue, never()).pollReady(any(Instant.class));
        verify(queue).awaitWork(anyLong());
        verify(queueManager, never()).tryAcquireSlot(anyString());
        verifyNoInteractions(dispatch);
    }

    @Test
    void tickOnce_whenSlotUnavailable_doesNotPollOrDispatch() {
        QueueManager queueManager = mock(QueueManager.class);
        SyncQueueService queue = mock(SyncQueueService.class);
        @SuppressWarnings("unchecked")
        Consumer<InvocationTask> dispatch = mock(Consumer.class);
        SyncQueueItem item = mock(SyncQueueItem.class);
        InvocationTask task = mock(InvocationTask.class);

        when(queue.peekReady(any(Instant.class))).thenReturn(item);
        when(item.task()).thenReturn(task);
        when(task.functionName()).thenReturn("fn");
        when(queueManager.tryAcquireSlot("fn")).thenReturn(false);

        SyncScheduler scheduler = new SyncScheduler(queueManager, queue, dispatch);
        scheduler.tickOnce();

        verify(queue, never()).pollReady(any(Instant.class));
        verify(queue, never()).recordDispatched(anyString(), any(Instant.class));
        verifyNoInteractions(dispatch);
    }

    @Test
    void tickOnce_whenPeekedItemDisappears_releasesSlot() {
        QueueManager queueManager = mock(QueueManager.class);
        SyncQueueService queue = mock(SyncQueueService.class);
        @SuppressWarnings("unchecked")
        Consumer<InvocationTask> dispatch = mock(Consumer.class);
        SyncQueueItem item = mock(SyncQueueItem.class);
        InvocationTask task = mock(InvocationTask.class);

        when(queue.peekReady(any(Instant.class))).thenReturn(item);
        when(item.task()).thenReturn(task);
        when(task.functionName()).thenReturn("fn");
        when(queueManager.tryAcquireSlot("fn")).thenReturn(true);
        when(queue.pollReady(any(Instant.class))).thenReturn(null);

        SyncScheduler scheduler = new SyncScheduler(queueManager, queue, dispatch);
        scheduler.tickOnce();

        verify(queueManager).releaseSlot("fn");
        verify(queue, never()).recordDispatched(anyString(), any(Instant.class));
        verifyNoInteractions(dispatch);
    }
}
