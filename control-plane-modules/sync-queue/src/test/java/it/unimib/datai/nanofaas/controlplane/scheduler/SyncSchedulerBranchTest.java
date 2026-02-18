package it.unimib.datai.nanofaas.controlplane.scheduler;

import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
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
        InvocationEnqueuer enqueuer = mock(InvocationEnqueuer.class);
        SyncQueueService queue = mock(SyncQueueService.class);
        @SuppressWarnings("unchecked")
        Consumer<InvocationTask> dispatch = mock(Consumer.class);

        when(queue.peekReady(any(Instant.class))).thenReturn(null);

        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, dispatch);
        scheduler.tickOnce();

        verify(queue, never()).pollReady(any(Instant.class));
        verify(queue).awaitWork(anyLong());
        verify(enqueuer, never()).tryAcquireSlot(anyString());
        verifyNoInteractions(dispatch);
    }

    @Test
    void tickOnce_whenSlotUnavailable_doesNotPollOrDispatch() {
        InvocationEnqueuer enqueuer = mock(InvocationEnqueuer.class);
        SyncQueueService queue = mock(SyncQueueService.class);
        @SuppressWarnings("unchecked")
        Consumer<InvocationTask> dispatch = mock(Consumer.class);
        SyncQueueItem item = mock(SyncQueueItem.class);
        InvocationTask task = mock(InvocationTask.class);

        when(queue.peekReady(any(Instant.class))).thenReturn(item);
        when(item.task()).thenReturn(task);
        when(task.functionName()).thenReturn("fn");
        when(enqueuer.tryAcquireSlot("fn")).thenReturn(false);

        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, dispatch);
        scheduler.tickOnce();

        verify(queue, never()).pollReady(any(Instant.class));
        verify(queue, never()).recordDispatched(anyString(), any(Instant.class));
        verifyNoInteractions(dispatch);
    }

    @Test
    void tickOnce_whenPeekedItemDisappears_releasesSlot() {
        InvocationEnqueuer enqueuer = mock(InvocationEnqueuer.class);
        SyncQueueService queue = mock(SyncQueueService.class);
        @SuppressWarnings("unchecked")
        Consumer<InvocationTask> dispatch = mock(Consumer.class);
        SyncQueueItem item = mock(SyncQueueItem.class);
        InvocationTask task = mock(InvocationTask.class);

        when(queue.peekReady(any(Instant.class))).thenReturn(item);
        when(item.task()).thenReturn(task);
        when(task.functionName()).thenReturn("fn");
        when(enqueuer.tryAcquireSlot("fn")).thenReturn(true);
        when(queue.pollReady(any(Instant.class))).thenReturn(null);

        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, dispatch);
        scheduler.tickOnce();

        verify(enqueuer).releaseSlot("fn");
        verify(queue, never()).recordDispatched(anyString(), any(Instant.class));
        verifyNoInteractions(dispatch);
    }
}
