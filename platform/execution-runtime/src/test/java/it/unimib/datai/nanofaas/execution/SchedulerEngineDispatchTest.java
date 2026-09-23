package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationQuotaExceededException;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The engine's dispatch protocol: one selection per tick, the claim/commit handshake against
 * {@link PendingWorkStore}, and the four ways a selection can end (no capacity, stale record,
 * input backpressure, submitted). Tests drive {@link SchedulerEngine#tick()} directly and never
 * start the worker thread.
 */
class SchedulerEngineDispatchTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");
    private static final FunctionGeneration GENERATION = new FunctionGeneration("echo", 1);

    private SchedulingIndex index;
    private EngineDispatch dispatch;
    private EngineReadiness readiness;
    private PendingWorkStore store;
    private SchedulerEngine engine;
    private SchedulingTicket ticket;
    private InvocationTask task;
    private InvocationTask leasedTask;
    private DispatchOwnership lease;

    @BeforeEach
    void setUp() {
        index = mock(SchedulingIndex.class);
        dispatch = mock(EngineDispatch.class);
        readiness = mock(EngineReadiness.class);
        when(readiness.runnable(any())).thenReturn(true);
        when(dispatch.isCurrent(any())).thenReturn(true);
        ticket = new SchedulingTicket(new TicketId("e1", 1), GENERATION, 0, NOW, NOW, null);
        task = mock(InvocationTask.class);
        leasedTask = mock(InvocationTask.class);
        lease = mock(DispatchOwnership.class);
        when(task.withDispatchLease(lease)).thenReturn(leasedTask);
        when(dispatch.tryAcquire(ticket)).thenReturn(lease);
        // A one-ticket stand-in for a real index: it honours the engine's runnable predicate and
        // the ticket's notBefore, which is what makes the blocked-generation assertions meaningful.
        when(index.select(any(), any())).thenAnswer(invocation -> {
            Instant now = invocation.getArgument(0);
            Predicate<FunctionGeneration> runnable = invocation.getArgument(1);
            return !ticket.notBefore().isAfter(now) && runnable.test(ticket.generation()) ? ticket : null;
        });
        store = new PendingWorkStore(4);
        engine = engineOver(store);
    }

    private SchedulerEngine engineOver(PendingWorkStore pendingWorkStore) {
        return engineOver(pendingWorkStore, () -> 0L);
    }

    /**
     * The tick-driven tests freeze nanoTime at 0, as the brief prescribes. The two tests that
     * actually start the worker thread pass {@link System#nanoTime} instead: a frozen monotonic
     * source makes a park's deadline unreachable, which is harmless when nothing ever parks but
     * would hang a running loop.
     */
    private SchedulerEngine engineOver(PendingWorkStore pendingWorkStore, LongSupplier nanoTime) {
        SchedulingStrategy strategy = mock(SchedulingStrategy.class);
        when(strategy.id()).thenReturn("test");
        when(strategy.newIndex()).thenReturn(index);
        return new SchedulerEngine(pendingWorkStore, new StrategyRegistry(List.of(strategy)), "test",
                dispatch, readiness, generation -> true, Clock.fixed(NOW, ZoneOffset.UTC), nanoTime);
    }

    @Test
    void selectionWithoutCapacityKeepsTheTicketAndItsReservation() {
        when(dispatch.tryAcquire(ticket)).thenReturn(null);

        engine.enqueue(new PendingEntry(ticket, task));
        engine.tick();

        assertThat(store.get(ticket.id())).isNotNull();
        assertThat(store.claimedCount()).isZero();
        assertThat(store.pendingCount()).isEqualTo(1);
        verify(dispatch, never()).submit(any());
        verify(index).defer(ticket.id());
    }

    @Test
    void aBlockedGenerationIsSkippedByFurtherPassesUntilItIsSignalled() {
        when(dispatch.tryAcquire(ticket)).thenReturn(null);
        engine.enqueue(new PendingEntry(ticket, task));

        engine.tick();
        engine.tick();

        // The second pass scanned, but the blocked generation was filtered out of the scan, so
        // the ticket was not re-selected and no second lease attempt was made. This is about
        // consecutive passes with no park in between; the park's own safety bound unblocks the
        // generation regardless of signals — see
        // aBlockedGenerationIsReExaminedAfterAParkTimeoutWithNoSignal.
        verify(index, times(2)).select(any(), any());
        verify(dispatch, times(1)).tryAcquire(ticket);
        verify(index, times(1)).defer(ticket.id());

        engine.signal();
        engine.tick();

        verify(dispatch, times(2)).tryAcquire(ticket);
    }

    @Test
    void aBlockedGenerationIsReExaminedAfterAParkTimeoutWithNoSignal() {
        when(dispatch.tryAcquire(ticket)).thenReturn(null);
        engine.enqueue(new PendingEntry(ticket, task));

        engine.tick();
        engine.tick();
        verify(dispatch, times(1)).tryAcquire(ticket);

        // runOnce is a pass plus its park. The engine's nanoTime is fixed at 0, so the park's
        // capacity-blocked safety bound is already elapsed when it is entered: no signal is sent,
        // no wake sequence advances, and capacity must still be re-examined on the next pass.
        engine.runOnce();
        engine.runOnce();

        verify(dispatch, times(2)).tryAcquire(ticket);
    }

    @Test
    void aThrowingLifecycleCalloutNeitherKillsTheWorkerNorStrandsTheClaim() throws Exception {
        CountDownLatch attempts = new CountDownLatch(3);
        when(dispatch.isCurrent(ticket)).thenAnswer(invocation -> {
            attempts.countDown();
            throw new IllegalStateException("lifecycle is unhappy");
        });
        SchedulerEngine running = engineOver(store, System::nanoTime);
        running.enqueue(new PendingEntry(ticket, task));

        running.start();
        try {
            // The loop survives the exception and keeps making passes, which is only possible if
            // the provisional claim was given back each time: a stranded claim is unselectable.
            assertThat(attempts.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            running.close();
        }
        assertThat(store.get(ticket.id())).isNotNull();
        assertThat(store.claimedCount()).isZero();
    }

    @Test
    void aWorkerKilledByAnErrorLeavesTheEngineRestartable() throws Exception {
        CountDownLatch attempts = new CountDownLatch(2);
        when(dispatch.isCurrent(ticket)).thenAnswer(invocation -> {
            attempts.countDown();
            throw new StackOverflowError("the JVM is in trouble");
        });
        SchedulerEngine running = engineOver(store, System::nanoTime);
        running.enqueue(new PendingEntry(ticket, task));

        running.start();
        try {
            // An Error is deliberately not swallowed, so it ends that worker. The engine must not
            // stay "running" with a dead thread, or start() would be a permanent no-op and every
            // queued invocation would stall with no way back. start() is a no-op only while a
            // worker is alive, so this retries until the dead one has retired itself.
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (attempts.getCount() > 0 && System.nanoTime() < deadlineNanos) {
                running.start();
                attempts.await(50, TimeUnit.MILLISECONDS);
            }
            assertThat(attempts.getCount()).isZero();
        } finally {
            running.close();
        }
    }

    @Test
    void aSubmitPathThatThrowsStillReleasesTheReservation() {
        // dispatchWithFailureCleanup itself throws: submit() fails, and the rejected() callback
        // it runs in its own finally throws on top. Without a finally around the settlement the
        // ticket would stay in `submitting` forever — reserved, in no index, unreapable.
        doThrow(new IllegalStateException("transport gone")).when(dispatch).submit(leasedTask);
        doThrow(new IllegalStateException("listener is unhappy"))
                .when(dispatch).rejected(any(), any());

        engine.enqueue(new PendingEntry(ticket, task));
        assertThatThrownBy(() -> engine.tick()).isInstanceOf(IllegalStateException.class);

        verify(lease).release();
        assertThat(store.submittingCount()).isZero();
        assertThat(store.get(ticket.id())).isNull();
        assertThat(engine.enqueue(new PendingEntry(ticket, task))).isTrue();
    }

    @Test
    void aSubmittedDispatchReleasesTheReservationAndLeavesTheIndex() {
        engine.enqueue(new PendingEntry(ticket, task));

        engine.tick();

        verify(dispatch).submit(leasedTask);
        verify(index).remove(ticket.id());
        assertThat(store.get(ticket.id())).isNull();
        assertThat(store.submittingCount()).isZero();
        verify(lease, never()).release();
    }

    @Test
    void aSubmitThatThrowsReturnsTheLeaseAndRejectsTheTask() {
        RuntimeException failure = new IllegalStateException("transport gone");
        doThrow(failure).when(dispatch).submit(leasedTask);

        engine.enqueue(new PendingEntry(ticket, task));
        engine.tick();

        verify(lease).release();
        verify(dispatch).rejected(leasedTask, failure);
        assertThat(store.get(ticket.id())).isNull();
        assertThat(store.submittingCount()).isZero();
    }

    @Test
    void exhaustedInputQuotaKeepsTheReservationAndRequeues() {
        doThrow(new InvocationQuotaExceededException(InvocationQuotaExceededException.Resource.INPUT))
                .when(dispatch).submit(leasedTask);

        engine.enqueue(new PendingEntry(ticket, task));
        engine.tick();

        verify(lease).release();
        verify(dispatch, never()).rejected(any(), any());
        assertThat(store.get(ticket.id())).isNotNull();
        assertThat(store.pendingCount()).isEqualTo(1);
        assertThat(store.submittingCount()).isZero();
        // Same ticket, same sequence and deadlines, back into the index that is active now.
        verify(index, times(2)).add(ticket);
    }

    @Test
    void aConcurrentEnqueueCannotTakeTheSlotReservedByAnInFlightSubmit() throws Exception {
        PendingWorkStore singleSlot = new PendingWorkStore(1);
        SchedulerEngine singleSlotEngine = engineOver(singleSlot);
        SchedulingTicket other = new SchedulingTicket(new TicketId("e2", 1), GENERATION, 1, NOW, NOW, null);
        AtomicBoolean secondAdmitted = new AtomicBoolean(true);
        doAnswer(invocation -> {
            // Another admitting thread while this submit is still in flight: the gate must be
            // free (or this join times out) and the reservation must still be held.
            Thread admitter = new Thread(() -> secondAdmitted.set(
                    singleSlotEngine.enqueue(new PendingEntry(other, mock(InvocationTask.class)))));
            admitter.start();
            admitter.join(5_000);
            assertThat(admitter.isAlive()).isFalse();
            return null;
        }).when(dispatch).submit(leasedTask);

        assertThat(singleSlotEngine.enqueue(new PendingEntry(ticket, task))).isTrue();
        singleSlotEngine.tick();

        assertThat(secondAdmitted).isFalse();
        verify(dispatch).submit(leasedTask);
    }

    @Test
    void theGateStaysFreeWhileALifecycleCalloutIsInFlight() {
        SchedulingTicket other = new SchedulingTicket(new TicketId("e2", 1), GENERATION, 1, NOW, NOW, null);
        CountDownLatch gateExercised = new CountDownLatch(1);
        doAnswer(invocation -> {
            // The gate is a leaf: it is never held across a call into the lifecycle, so a thread
            // that owns some other lock can still admit, signal and withdraw work here. If the
            // engine held its gate around submit, this await would time out instead.
            Thread stranger = new Thread(() -> {
                engine.signal();
                engine.enqueue(new PendingEntry(other, mock(InvocationTask.class)));
                engine.remove(other.id());
                gateExercised.countDown();
            });
            stranger.start();
            assertThat(gateExercised.await(5, TimeUnit.SECONDS)).isTrue();
            stranger.join(5_000);
            return null;
        }).when(dispatch).submit(leasedTask);

        engine.enqueue(new PendingEntry(ticket, task));
        engine.tick();

        verify(dispatch).submit(leasedTask);
        assertThat(store.get(other.id())).isNull();
    }

    @Test
    void aStaleTicketIsDroppedWithoutAcquiringCapacity() {
        when(dispatch.isCurrent(ticket)).thenReturn(false);

        engine.enqueue(new PendingEntry(ticket, task));
        engine.tick();

        verify(dispatch, never()).tryAcquire(any());
        verify(dispatch).removed(task);
        verify(index).remove(ticket.id());
        assertThat(store.get(ticket.id())).isNull();
    }

    @Test
    void removalDuringSubmitCancelsTheRequeueOfThatCommittedDispatch() {
        doAnswer(invocation -> {
            engine.remove(ticket.id());
            throw new InvocationQuotaExceededException(InvocationQuotaExceededException.Resource.INPUT);
        }).when(dispatch).submit(leasedTask);

        engine.enqueue(new PendingEntry(ticket, task));
        engine.tick();

        assertThat(store.get(ticket.id())).isNull();
        assertThat(store.pendingCount()).isZero();
        assertThat(store.submittingCount()).isZero();
        verify(index, times(1)).add(ticket);
        verify(dispatch).removed(task);
    }

    @Test
    void aTicketPastItsQueueDeadlineIsExpiredBeforeAnySelection() {
        SchedulingTicket expiring = new SchedulingTicket(new TicketId("e3", 1), GENERATION, 2,
                NOW.minusSeconds(10), NOW.minusSeconds(10), NOW.minusSeconds(1));
        // doReturn, not when(...): re-stubbing through when() would invoke the setUp answer with
        // null arguments.
        doReturn(null).when(index).select(any(), any());

        engine.enqueue(new PendingEntry(expiring, task));
        engine.tick();

        verify(dispatch).expired(task);
        verify(index).remove(expiring.id());
        assertThat(store.get(expiring.id())).isNull();
        verify(dispatch, never()).tryAcquire(any());
    }

    @Test
    void anEnqueueBeyondTheReservationCapIsRefusedWithoutTouchingTheIndex() {
        PendingWorkStore singleSlot = new PendingWorkStore(1);
        SchedulerEngine singleSlotEngine = engineOver(singleSlot);
        SchedulingTicket other = new SchedulingTicket(new TicketId("e2", 1), GENERATION, 1, NOW, NOW, null);

        assertThat(singleSlotEngine.enqueue(new PendingEntry(ticket, task))).isTrue();
        assertThat(singleSlotEngine.enqueue(new PendingEntry(other, task))).isFalse();

        verify(index).add(ticket);
        verify(index, never()).add(other);
    }
}
