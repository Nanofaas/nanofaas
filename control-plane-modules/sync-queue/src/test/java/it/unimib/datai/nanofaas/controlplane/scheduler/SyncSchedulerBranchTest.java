package it.unimib.datai.nanofaas.controlplane.scheduler;

import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueItem;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        when(queue.pollReadyMatching(any(Instant.class), any())).thenReturn(null);
        when(queue.peekReady(any(Instant.class))).thenReturn(mock(SyncQueueItem.class));

        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, dispatch);
        scheduler.tickOnce();

        verify(queue, never()).pollReady(any(Instant.class));
        verify(queue, never()).recordDispatched(anyString(), any(Instant.class));
        verifyNoInteractions(dispatch);
    }

    @Test
    void tickOnce_whenWorkAdvances_resetsBlockedBackoff() {
        InvocationEnqueuer enqueuer = mock(InvocationEnqueuer.class);
        SyncQueueService queue = mock(SyncQueueService.class);
        @SuppressWarnings("unchecked")
        Consumer<InvocationTask> dispatch = mock(Consumer.class);
        List<Long> pauses = new ArrayList<>();
        SyncQueueItem item = mock(SyncQueueItem.class);
        InvocationTask task = mock(InvocationTask.class);

        when(queue.pollReadyMatching(any(Instant.class), any())).thenReturn(null, item, null);
        when(item.task()).thenReturn(task);
        when(task.functionName()).thenReturn("fn");
        when(queue.peekReady(any(Instant.class))).thenReturn(mock(SyncQueueItem.class), mock(SyncQueueItem.class));

        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, dispatch, pauses::add);
        scheduler.tickOnce();
        scheduler.tickOnce();
        scheduler.tickOnce();

        assertThat(pauses).containsExactly(2L, 2L);
        verify(queue).recordDispatched(eq("fn"), any(Instant.class));
        verify(dispatch).accept(task);
    }

    @Test
    void tickOnce_whenWorkCannotAdvance_usesGrowingBackoff() {
        InvocationEnqueuer enqueuer = mock(InvocationEnqueuer.class);
        SyncQueueService queue = mock(SyncQueueService.class);
        @SuppressWarnings("unchecked")
        Consumer<InvocationTask> dispatch = mock(Consumer.class);
        List<Long> pauses = new ArrayList<>();

        when(queue.pollReadyMatching(any(Instant.class), any())).thenReturn(null);
        when(queue.peekReady(any(Instant.class))).thenReturn(mock(SyncQueueItem.class));

        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, dispatch, pauses::add);

        scheduler.tickOnce();
        scheduler.tickOnce();
        scheduler.tickOnce();

        assertThat(pauses).containsExactly(2L, 4L, 8L);
        verify(queue, never()).awaitWork(anyLong());
        verifyNoInteractions(dispatch);
    }
}
