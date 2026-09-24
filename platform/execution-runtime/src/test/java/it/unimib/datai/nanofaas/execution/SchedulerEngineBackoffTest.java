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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchedulerEngineBackoffTest {
    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final FunctionGeneration GENERATION = new FunctionGeneration("echo", 1);
    private final AtomicReference<Instant> clockNow = new AtomicReference<>(NOW);
    private final Map<TicketId, SchedulingTicket> ready = new LinkedHashMap<>();
    private final AtomicReference<LongSupplier> nanoTime = new AtomicReference<>(System::nanoTime);
    private SchedulingIndex index;
    private EngineDispatch dispatch;
    private PendingWorkStore store;
    private SchedulerEngine engine;
    private InvocationTask task;
    private InvocationTask leasedTask;
    private DispatchOwnership lease;

    @BeforeEach
    void setUp() {
        index = mock(SchedulingIndex.class);
        dispatch = mock(EngineDispatch.class);
        EngineReadiness readiness = mock(EngineReadiness.class);
        when(readiness.runnable(any())).thenReturn(true);
        task = mock(InvocationTask.class);
        leasedTask = mock(InvocationTask.class);
        lease = mock(DispatchOwnership.class);
        when(task.withDispatchLease(lease)).thenReturn(leasedTask);
        when(dispatch.tryAcquire(any())).thenReturn(lease);
        doAnswer(call -> {
            SchedulingTicket ticket = call.getArgument(0);
            assertThat(ready.putIfAbsent(ticket.id(), ticket)).isNull();
            return null;
        }).when(index).add(any());
        doAnswer(call -> ready.remove(call.getArgument(0))).when(index).remove(any());
        when(index.select(any(), any())).thenAnswer(call -> {
            Instant now = call.getArgument(0);
            Predicate<FunctionGeneration> runnable = call.getArgument(1);
            return ready.values().stream().filter(t -> !t.notBefore().isAfter(now)
                    && runnable.test(t.generation())).findFirst().orElse(null);
        });
        SchedulingStrategy strategy = mock(SchedulingStrategy.class);
        when(strategy.id()).thenReturn("test");
        when(strategy.newIndex()).thenReturn(index);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(call -> clockNow.get());
        store = spy(new PendingWorkStore(100));
        engine = new SchedulerEngine(store, new StrategyRegistry(List.of(strategy)), "test",
                dispatch, readiness, generation -> true, clock, () -> nanoTime.get().getAsLong());
    }

    private SchedulingTicket admit(int id, Instant due, Instant deadline) {
        SchedulingTicket ticket = new SchedulingTicket(new TicketId("e" + id, 2), GENERATION,
                id, NOW, due, deadline);
        assertThat(engine.enqueue(new PendingEntry(ticket, task))).isTrue();
        return ticket;
    }

    @Test
    void unrelatedTerminalCompletionDoesNotVisitTheBacklog() {
        for (int i = 0; i < 65; i++) admit(i, NOW.plusSeconds(1), null);
        clearInvocations(store, index, dispatch);

        engine.removeExecution("unrelated");

        verifyNoInteractions(store, index, dispatch);
        assertThat(store.reservedCount()).isEqualTo(65);
    }

    @Test
    void terminalCompletionOnlyRemovesItsTicketsWithoutSnapshottingTheBacklog() {
        for (int i = 0; i < 65; i++) admit(i, NOW.plusSeconds(1), null);
        clearInvocations(store);

        engine.removeExecution("e32");

        verify(store, never()).snapshotAll();
        verify(store, never()).snapshotPending();
        verify(store).remove(new TicketId("e32", 2));
        verify(dispatch).removed(task);
        assertThat(store.reservedCount()).isEqualTo(64);
        clockNow.set(NOW.plusSeconds(1));
        for (int i = 0; i < 65; i++) engine.tick();
        verify(dispatch, times(64)).submit(any());
        assertThat(store.reservedCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void terminalCompletionFindsRetryPublishedWhilePreviousAttemptIsSubmitting(boolean backpressured) {
        SchedulingTicket first = admit(1, NOW, null);
        SchedulingTicket retry = new SchedulingTicket(new TicketId("e1", 3), GENERATION,
                2, NOW, NOW.plusSeconds(1), null);
        doAnswer(call -> {
            assertThat(engine.enqueue(new PendingEntry(retry, task))).isTrue();
            engine.removeExecution(first.id().executionId());
            if (backpressured) {
                throw new InvocationQuotaExceededException(InvocationQuotaExceededException.Resource.INPUT);
            }
            return null;
        }).when(dispatch).submit(any());

        engine.tick();
        clockNow.set(NOW.plusSeconds(1));
        engine.tick();

        verify(dispatch).submit(any());
        verify(dispatch, times(backpressured ? 2 : 1)).removed(task);
        assertThat(store.reservedCount()).isZero();
        clearInvocations(store);
        engine.removeExecution("e1");
        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ticket", "function", "execution", "expiry", "submit", "dispose"})
    void settledTicketsLeaveNoTerminalLookupEntry(String settlement) {
        SchedulingTicket ticket = admit(1, NOW, NOW.plusSeconds(1));
        switch (settlement) {
            case "ticket" -> engine.remove(ticket.id());
            case "function" -> engine.removeAllFor("echo");
            case "execution" -> engine.removeExecution("e1");
            case "expiry" -> {
                clockNow.set(NOW.plusSeconds(1));
                engine.tick();
            }
            case "submit" -> engine.tick();
            case "dispose" -> engine.dispose();
            default -> throw new AssertionError(settlement);
        }
        assertThat(store.reservedCount()).isZero();
        clearInvocations(store, index, dispatch);

        engine.removeExecution("e1");

        verifyNoInteractions(store, index, dispatch);
    }

    @Test
    void finishingPreviousSubmitKeepsPublishedRetryReachableForTerminalRemoval() {
        admit(1, NOW, null);
        SchedulingTicket retry = new SchedulingTicket(new TicketId("e1", 3), GENERATION,
                2, NOW, NOW.plusSeconds(1), null);
        doAnswer(call -> {
            assertThat(engine.enqueue(new PendingEntry(retry, task))).isTrue();
            assertThat(engine.enqueue(new PendingEntry(retry, task))).isFalse();
            return null;
        }).when(dispatch).submit(any());
        engine.tick();
        assertThat(store.reservedCount()).isEqualTo(1);

        engine.removeExecution("e1");
        clockNow.set(NOW.plusSeconds(1));
        engine.tick();

        verify(dispatch).submit(any());
        verify(dispatch).removed(task);
        assertThat(store.reservedCount()).isZero();
    }

    @Test
    void futureTicketNeverEntersReadyIndexAtAdmission() {
        SchedulingTicket future = admit(1, NOW.plusSeconds(1), null);
        verify(index, never()).add(future);
        assertThat(store.reservedCount()).isEqualTo(1);
        engine.tick();
        verify(dispatch, never()).submit(any());
        clockNow.set(NOW.plusMillis(999));
        engine.tick();
        verify(dispatch, never()).submit(any());
        clockNow.set(NOW.plusSeconds(1));
        engine.tick();
        verify(dispatch).submit(leasedTask);
        assertThat(store.reservedCount()).isZero();
    }

    @Test
    void promotesAtMost64PerPassAndDrainsEveryTicketExactlyOnce() {
        for (int i = 0; i < 65; i++) admit(i, NOW.plusSeconds(1), null);
        verify(index, never()).add(any());
        clockNow.set(NOW.plusSeconds(1));
        engine.tick();
        verify(index, times(64)).add(any());
        verify(dispatch).submit(any());
        for (int i = 0; i < 65; i++) engine.tick();
        verify(index, times(65)).add(any());
        verify(dispatch, times(65)).submit(any());
        assertThat(store.reservedCount()).isZero();
    }

    @Test
    void expiryWinsAtEligibilityAndRemainsBounded() {
        for (int i = 0; i < 65; i++) admit(i, NOW.plusSeconds(1), NOW.plusSeconds(1));
        clockNow.set(NOW.plusSeconds(1));
        engine.tick();
        verify(dispatch, times(64)).expired(task);
        assertThat(store.reservedCount()).isEqualTo(1);
        verify(index, never()).add(any());
        engine.tick();
        engine.tick();
        verify(dispatch, times(65)).expired(task);
        verify(dispatch, never()).submit(any());
        assertThat(store.reservedCount()).isZero();
    }

    @Test
    void backwardClockDuringLeaseAcquisitionReturnsTicketToDelay() {
        SchedulingTicket ticket = admit(1, NOW, null);
        doAnswer(call -> {
            clockNow.set(NOW.minusSeconds(1));
            return lease;
        }).when(dispatch).tryAcquire(any());
        engine.tick();
        verify(dispatch, never()).submit(any());
        verify(lease).release();
        assertThat(ready).isEmpty();
        assertThat(store.reservedCount()).isEqualTo(1);
        doReturn(lease).when(dispatch).tryAcquire(any());
        clockNow.set(NOW);
        engine.tick();
        verify(dispatch).submit(any());
        assertThat(store.get(ticket.id())).isNull();
    }

    @Test
    void promotionFailureRetainsReservationAndCanRetry() {
        SchedulingTicket ticket = admit(1, NOW.plusSeconds(1), null);
        doThrow(new IllegalStateException("injected")).when(index).add(ticket);
        clockNow.set(NOW.plusSeconds(1));
        assertThatThrownBy(engine::tick).hasMessage("injected");
        assertThat(store.reservedCount()).isEqualTo(1);
        doAnswer(call -> { ready.put(ticket.id(), ticket); return null; }).when(index).add(ticket);
        engine.tick();
        verify(dispatch).submit(any());
        assertThat(store.reservedCount()).isZero();
    }

    @Test
    void failedAdmissionReleasesReservationWithoutLifecycleCallout() {
        SchedulingTicket ticket = new SchedulingTicket(new TicketId("failure", 2), GENERATION,
                1, NOW, NOW, null);
        doThrow(new IllegalStateException("injected")).when(index).add(ticket);
        assertThatThrownBy(() -> engine.enqueue(new PendingEntry(ticket, task))).hasMessage("injected");
        assertThat(store.reservedCount()).isZero();
        verify(dispatch, never()).removed(any());
    }

    @Test
    void inputBackpressureAfterBackwardClockRequeuesIntoDelay() {
        admit(1, NOW, null);
        doAnswer(call -> {
            clockNow.set(NOW.minusSeconds(1));
            throw new InvocationQuotaExceededException(InvocationQuotaExceededException.Resource.INPUT);
        }).when(dispatch).submit(any());
        engine.tick();
        assertThat(ready).isEmpty();
        assertThat(store.reservedCount()).isEqualTo(1);
        doNothing().when(dispatch).submit(any());
        engine.tick();
        verify(dispatch).submit(any());
        clockNow.set(NOW);
        engine.tick();
        verify(dispatch, times(2)).submit(any());
        assertThat(store.reservedCount()).isZero();
    }
    @ParameterizedTest
    @ValueSource(strings = {"ticket", "function", "execution"})
    void repeatedRemovalReleasesDelayedReservationExactlyOnce(String kind) {
        SchedulingTicket ticket = admit(1, NOW.plusSeconds(1), null);
        for (int i = 0; i < 2; i++) {
            switch (kind) {
                case "ticket" -> engine.remove(ticket.id());
                case "function" -> engine.removeAllFor("echo");
                case "execution" -> engine.removeExecution(ticket.id().executionId());
                default -> throw new AssertionError(kind);
            }
        }
        clockNow.set(NOW.plusSeconds(2));
        engine.tick();
        verify(dispatch).removed(task);
        verify(dispatch, never()).submit(any());
        assertThat(store.reservedCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"execution", "dispose"})
    void terminalRemovalDuringSubmittingPreventsBackpressureRequeue(String kind) {
        SchedulingTicket ticket = admit(1, NOW, null);
        doAnswer(call -> {
            if (kind.equals("execution")) engine.removeExecution(ticket.id().executionId());
            else engine.dispose();
            throw new InvocationQuotaExceededException(InvocationQuotaExceededException.Resource.INPUT);
        }).when(dispatch).submit(any());
        engine.tick();
        engine.tick();
        verify(dispatch).removed(task);
        verify(dispatch).submit(any());
        assertThat(store.reservedCount()).isZero();
    }

    @Test
    void terminalRemovalDuringClaimReleasesLeaseWithoutDispatch() {
        SchedulingTicket ticket = admit(1, NOW, null);
        doAnswer(call -> { engine.removeExecution(ticket.id().executionId()); return lease; })
                .when(dispatch).tryAcquire(any());
        engine.tick();
        verify(dispatch).removed(task);
        verify(dispatch, never()).submit(any());
        verify(lease).release();
        assertThat(store.reservedCount()).isZero();
    }

    @Test
    void closeRetainsFutureWorkAndRestartDispatchesWhenDue() throws Exception {
        admit(1, NOW.plusSeconds(1), null);
        engine.start();
        engine.close();
        assertThat(store.reservedCount()).isEqualTo(1);
        verify(dispatch, never()).submit(any());
        CountDownLatch submitted = new CountDownLatch(1);
        doAnswer(call -> { submitted.countDown(); return null; }).when(dispatch).submit(any());
        clockNow.set(NOW.plusSeconds(1));
        engine.start();
        try {
            assertThat(submitted.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            engine.close();
        }
        assertThat(store.reservedCount()).isZero();
    }

    @Test
    void disposeReleasesFutureWorkAndRefusesRestartAndAdmission() {
        SchedulingTicket ticket = admit(1, Instant.MAX, null);
        engine.dispose();
        engine.dispose();
        verify(dispatch).removed(task);
        assertThat(store.reservedCount()).isZero();
        assertThat(engine.enqueue(new PendingEntry(ticket, task))).isFalse();
        assertThatThrownBy(engine::start).isInstanceOf(IllegalStateException.class);
        clockNow.set(Instant.MAX);
        engine.tick();
        verify(dispatch, never()).submit(any());
    }

    @Test
    void idleBudgetBoundsFarFutureAndRoundsUpEarlierEvents() {
        admit(1, Instant.MAX, null);
        assertThat(engine.idleBudgetMs()).isEqualTo(SchedulerEngine.CAPACITY_BLOCKED_AWAIT_MS);
        admit(2, NOW.plusNanos(1), null);
        assertThat(engine.idleBudgetMs()).isEqualTo(1);
        clockNow.set(NOW.plusNanos(1));
        assertThat(engine.idleBudgetMs()).isZero();
    }

    @Test
    void idleBudgetUsesQueueDeadlineBeforeEligibility() {
        admit(1, Instant.MAX, NOW.plusMillis(20));
        assertThat(engine.idleBudgetMs()).isEqualTo(20);
        clockNow.set(NOW.plusMillis(20));
        assertThat(engine.idleBudgetMs()).isZero();
        engine.tick();
        verify(dispatch).expired(task);
        verify(dispatch, never()).submit(any());
    }

    @Test
    void earlierInsertionBetweenPassAndParkCannotLoseWakeSequence() throws Exception {
        admit(1, Instant.MAX, null);
        CountDownLatch beforePark = new CountDownLatch(1);
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch submitted = new CountDownLatch(1);
        nanoTime.set(() -> {
            beforePark.countDown();
            try {
                assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            // Frozen monotonic time makes a lost wake wait forever, even across safety parks.
            return 0;
        });
        doAnswer(call -> { submitted.countDown(); return null; }).when(dispatch).submit(any());
        engine.start();
        try {
            assertThat(beforePark.await(5, TimeUnit.SECONDS)).isTrue();
            admit(2, NOW.plusMillis(10), null);
            clockNow.set(NOW.plusMillis(10));
            inserted.countDown();
            assertThat(submitted.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            inserted.countDown();
            engine.close();
        }
        verify(dispatch).submit(any());
        assertThat(store.reservedCount()).isEqualTo(1);
    }

    @Test
    void failedPromotionStillDeliversAlreadyReapedExpiry() {
        admit(1, NOW.plusSeconds(1), NOW.plusSeconds(1));
        SchedulingTicket promoted = admit(2, NOW.plusSeconds(1), null);
        doThrow(new IllegalStateException("injected")).when(index).add(promoted);
        clockNow.set(NOW.plusSeconds(1));
        assertThatThrownBy(engine::tick).hasMessage("injected");
        verify(dispatch).expired(task);
        assertThat(store.reservedCount()).isEqualTo(1);
    }

}
