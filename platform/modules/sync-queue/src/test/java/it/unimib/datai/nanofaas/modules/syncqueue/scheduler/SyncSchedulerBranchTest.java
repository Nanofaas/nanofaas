package it.unimib.datai.nanofaas.modules.syncqueue.scheduler;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.QueuedDispatchCapacity;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueItem;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationDispatch;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The scheduler's wait branches, driven with a mocked queue so no real park is involved: the
 * assertions pin down that the worker parks through the queue's notifiable wait (never a sleep
 * or a poll) and re-scans on the next tick.
 */
class SyncSchedulerBranchTest {

    @Test
    void tickOnce_whenQueueEmpty_parksForWorkWithoutDispatching() {
        QueuedDispatchCapacity enqueuer = it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.enqueuer();
        SyncQueueService queue = mock(SyncQueueService.class);
        @SuppressWarnings("unchecked")
        InvocationDispatch dispatch = mock(InvocationDispatch.class);

        when(queue.peekReady(any(Instant.class))).thenReturn(null);

        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, dispatch, org.mockito.Mockito.mock(it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle.class));
        scheduler.tickOnce();

        // The worker must not busy-poll: it records the notification sequence before the scan
        // and parks on the queue's monitor with a safety timeout when it finds no work.
        verify(queue).wakeupEpoch();
        verify(queue).awaitWakeup(anyLong(), anyLong());
        verify(queue, never()).pollReady(any(Instant.class));
        verify(enqueuer, never()).tryAcquireLease(any());
        verifyNoInteractions(dispatch);
    }

    @Test
    void tickOnce_whenQueueBlockedOnCapacity_rotatesWindowThenParks() {
        QueuedDispatchCapacity enqueuer = it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.enqueuer();
        SyncQueueService queue = mock(SyncQueueService.class);
        @SuppressWarnings("unchecked")
        InvocationDispatch dispatch = mock(InvocationDispatch.class);

        when(queue.findReadyMatching(any(Instant.class), any())).thenReturn(null);
        when(queue.peekReady(any(Instant.class))).thenReturn(mock(SyncQueueItem.class));

        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, dispatch, org.mockito.Mockito.mock(it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle.class));
        scheduler.tickOnce();

        // Work exists but nothing in the scan window can dispatch: rotate the window (so items
        // queued beyond the scan limit are eventually reached) and park on work/capacity rather
        // than sleeping a backoff.
        verify(queue).rotateReadyScanWindow(any(Instant.class));
        verify(queue).awaitWakeup(anyLong(), anyLong());
        verify(queue, never()).pollReady(any(Instant.class));
        verify(queue, never()).recordDispatched(anyString(), any(Instant.class));
        verifyNoInteractions(dispatch);
    }

    @Test
    void tickOnce_whenFinalSlotAcquisitionFails_rotatesItemThenParks() {
        QueuedDispatchCapacity enqueuer = it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.enqueuer();
        SyncQueueService queue = mock(SyncQueueService.class);
        @SuppressWarnings("unchecked")
        InvocationDispatch dispatch = mock(InvocationDispatch.class);

        SyncQueueItem item = mock(SyncQueueItem.class);
        InvocationTask task = new InvocationTask("test", "fn", null, null, null, null, Instant.now(), 1, it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind.SYNC);
        when(queue.findReadyMatching(any(Instant.class), any())).thenReturn(item);
        when(item.task()).thenReturn(task);
        it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.allow(enqueuer, "fn", false);

        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, dispatch, org.mockito.Mockito.mock(it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle.class));
        scheduler.tickOnce();

        // The slot went between the scan and the acquire: the item is put back and the worker
        // parks on capacity instead of re-trying in a tight loop.
        verify(queue).rotateReadyItem(eq(item), any(Instant.class));
        verify(queue).awaitWakeup(anyLong(), anyLong());
        verifyNoInteractions(dispatch);
    }

    @Test
    void tickOnce_whenSlotAcquiredButRemovalFails_releasesSlotWithoutParking() {
        QueuedDispatchCapacity enqueuer = it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.enqueuer();
        SyncQueueService queue = mock(SyncQueueService.class);
        @SuppressWarnings("unchecked")
        InvocationDispatch dispatch = mock(InvocationDispatch.class);

        SyncQueueItem item = mock(SyncQueueItem.class);
        InvocationTask task = new InvocationTask("test", "fn", null, null, null, null, Instant.now(), 1, it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind.SYNC);
        when(queue.findReadyMatching(any(Instant.class), any())).thenReturn(item);
        when(item.task()).thenReturn(task);
        it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.allow(enqueuer, "fn", true);
        when(queue.removeReadyForDispatch(eq(item), any(Instant.class))).thenReturn(false);

        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, dispatch, org.mockito.Mockito.mock(it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle.class));
        scheduler.tickOnce();

        // Another thread removed the item after the slot was taken: a one-time race, so the
        // slot is given back and the loop re-scans immediately (no park needed).
        org.junit.jupiter.api.Assertions.assertEquals(1, it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.released(enqueuer, "fn"));
        verify(queue, never()).awaitWakeup(anyLong(), anyLong());
        verifyNoInteractions(dispatch);
    }

    @Test
    void tickOnce_whenDispatchSucceeds_dispatchesWithoutParking() {
        QueuedDispatchCapacity enqueuer = it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.enqueuer();
        SyncQueueService queue = mock(SyncQueueService.class);
        @SuppressWarnings("unchecked")
        InvocationDispatch dispatch = mock(InvocationDispatch.class);

        SyncQueueItem item = mock(SyncQueueItem.class);
        InvocationTask task = new InvocationTask("test", "fn", null, null, null, null, Instant.now(), 1, it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind.SYNC);
        when(queue.findReadyMatching(any(Instant.class), any())).thenReturn(item);
        when(queue.removeReadyForDispatch(eq(item), any(Instant.class))).thenReturn(true);
        when(item.task()).thenReturn(task);
        it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.allow(enqueuer, "fn", true);

        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, dispatch, org.mockito.Mockito.mock(it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle.class));
        scheduler.tickOnce();

        verify(queue).recordDispatched(eq("fn"), any(Instant.class));
        verify(dispatch).dispatch(argThat(actual -> actual.withDispatchLease(null).equals(task)));
        verify(queue).completeDispatchReservation(item);
        verify(queue, never()).awaitWakeup(anyLong(), anyLong());
    }
}
