package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SchedulerEngine#snapshotQueues()}, {@link SchedulerEngine#reservedCount}, the
 * drain-listener lifecycle and the {@link SchedulerEngine.SwitchObserver} callback (Task 11,
 * issue #208).
 */
class SchedulerEngineQueueSnapshotTest {

    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");
    private static final FunctionGeneration ECHO = new FunctionGeneration("echo", 1);

    private EngineDispatch dispatch;
    private EngineReadiness readiness;
    private DispatchOwnership lease;
    private PendingWorkStore store;
    private SchedulerEngine engine;

    @BeforeEach
    void setUp() {
        lease = mock(DispatchOwnership.class);
        readiness = mock(EngineReadiness.class);
        when(readiness.runnable(any())).thenReturn(true);
        dispatch = mock(EngineDispatch.class);
        when(dispatch.isCurrent(any())).thenReturn(true);
        when(dispatch.tryAcquire(any())).thenReturn(lease);
        store = new PendingWorkStore(64);
        SchedulingStrategy fifo = fifoStrategy("per-function");
        SchedulingStrategy other = fifoStrategy("shared-queue");
        engine = new SchedulerEngine(store, new StrategyRegistry(List.of(fifo, other)), "per-function",
                dispatch, readiness, Clock.fixed(NOW, ZoneOffset.UTC), () -> 0L);
    }

    private static SchedulingStrategy fifoStrategy(String id) {
        SchedulingStrategy strategy = mock(SchedulingStrategy.class);
        when(strategy.id()).thenReturn(id);
        when(strategy.newIndex()).thenAnswer(invocation -> new FifoIndex());
        return strategy;
    }

    private SchedulingTicket admit(String executionId, long sequence) {
        SchedulingTicket ticket = new SchedulingTicket(new TicketId(executionId, 1), ECHO,
                sequence, NOW, NOW, null);
        InvocationTask work = new InvocationTask(executionId, ECHO.functionName(), null, null,
                null, null, NOW, 1, InvocationKind.ASYNC);
        assertThat(engine.enqueue(new PendingEntry(ticket, work))).isTrue();
        return ticket;
    }

    @Test
    void snapshotCountsPendingClaimedAndSubmittingSeparately() {
        admit("e1", 0);
        admit("e2", 1);
        admit("e3", 2);

        EngineQueueSnapshot before = engine.snapshotQueues();
        assertThat(before.pending()).isEqualTo(3);
        assertThat(before.claimed()).isZero();
        assertThat(before.submitting()).isZero();
        assertThat(before.delayed()).isZero();
        assertThat(before.perGeneration()).isEqualTo(java.util.Map.of(ECHO, 3));
    }

    @Test
    void aSwitchNeverDuplicatesThePendingPopulation() {
        admit("e1", 0);
        admit("e2", 1);
        EngineQueueSnapshot before = engine.snapshotQueues();

        engine.switchTo("shared-queue");

        assertThat(engine.snapshotQueues().pending()).isEqualTo(before.pending());
    }

    @Test
    void reservedCountTracksAdmissionThroughSettlementWithoutScanningTheBacklog() {
        assertThat(engine.reservedCount("echo")).isZero();

        admit("e1", 0);
        assertThat(engine.reservedCount("echo")).isEqualTo(1);

        // dispatch.submit is a no-op mock (no exception), so the tick carries this ticket all
        // the way to a successful, immediate finishSubmit — the reservation is released in the
        // same pass, exactly like a real dispatch that neither backpressures nor throws.
        engine.tick();
        assertThat(engine.reservedCount("echo")).isZero();
    }

    @Test
    void removingAPendingTicketReleasesItsReservation() {
        SchedulingTicket ticket = admit("e1", 0);
        assertThat(engine.reservedCount("echo")).isEqualTo(1);

        engine.remove(ticket.id());

        assertThat(engine.reservedCount("echo")).isZero();
    }

    @Test
    void aDrainListenerWaitsForAStillActiveReservationBeforeFiring() {
        // The generation is never runnable, so the admitted ticket is never selected/dispatched
        // and its reservation stays open — standing in for "a physically active attempt still
        // holds a lease", without needing to drive dispatch.submit's own lifecycle.
        when(readiness.runnable(any())).thenReturn(false);
        SchedulingTicket ticket = admit("e1", 0);
        List<String> drained = new ArrayList<>();
        engine.addDrainListener(drained::add);

        engine.markDraining("echo");
        engine.tick();
        assertThat(drained).isEmpty();

        engine.remove(ticket.id());
        engine.tick();
        assertThat(drained).containsExactly("echo");
    }

    @Test
    void clearDrainingCancelsAPendingDrainForARenewedRegistration() {
        List<String> drained = new ArrayList<>();
        engine.addDrainListener(drained::add);

        // Nothing admitted: reservedCount("echo") is already zero, so an un-cancelled
        // markDraining would fire on the very next pass.
        engine.markDraining("echo");
        engine.clearDraining("echo");
        engine.tick();

        assertThat(drained).isEmpty();
    }

    @Test
    void switchObserverSeesCommittedNoopAndRefusedOutcomes() {
        List<SchedulerEngine.SwitchOutcome> outcomes = new ArrayList<>();
        AtomicReference<Long> lastDuration = new AtomicReference<>();
        engine.setSwitchObserver((strategy, outcome, durationNanos) -> {
            outcomes.add(outcome);
            lastDuration.set(durationNanos);
        });

        engine.switchTo("shared-queue");
        assertThat(outcomes).containsExactly(SchedulerEngine.SwitchOutcome.COMMITTED);
        assertThat(lastDuration.get()).isNotNull();

        engine.switchTo("shared-queue");
        assertThat(outcomes).containsExactly(
                SchedulerEngine.SwitchOutcome.COMMITTED, SchedulerEngine.SwitchOutcome.NOOP);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> engine.switchTo("does-not-exist"))
                .isInstanceOf(IllegalArgumentException.class);
        // An unknown strategy is rejected before the observer's own strategy id can even be
        // resolved (strategies.require throws first) — outcomes therefore stays exactly as above.
        assertThat(outcomes).hasSize(2);
    }

    @Test
    void aThrowingSwitchObserverNeverFailsTheSwitchItself() {
        engine.setSwitchObserver((strategy, outcome, durationNanos) -> {
            throw new IllegalStateException("boom");
        });

        engine.switchTo("shared-queue");

        assertThat(engine.snapshot().strategy()).isEqualTo("shared-queue");
    }

    /** Plain FIFO, oldest-first, honouring {@code notBefore} and the runnable predicate. */
    private static final class FifoIndex implements SchedulingIndex {
        private final Deque<SchedulingTicket> queue = new ArrayDeque<>();
        private final Set<TicketId> present = new HashSet<>();

        @Override
        public void add(SchedulingTicket ticket) {
            if (present.add(ticket.id())) {
                queue.addLast(ticket);
            }
        }

        @Override
        public void remove(TicketId id) {
            if (present.remove(id)) {
                queue.removeIf(ticket -> ticket.id().equals(id));
            }
        }

        @Override
        public SchedulingTicket select(Instant now, Predicate<FunctionGeneration> runnable) {
            for (SchedulingTicket ticket : queue) {
                if (!ticket.notBefore().isAfter(now) && runnable.test(ticket.generation())) {
                    return ticket;
                }
            }
            return null;
        }

        @Override
        public void defer(TicketId id) {
            queue.stream().filter(ticket -> ticket.id().equals(id)).findFirst().ifPresent(ticket -> {
                queue.remove(ticket);
                queue.addLast(ticket);
            });
        }

        @Override
        public int size() {
            return present.size();
        }

        @Override
        public void clear() {
            queue.clear();
            present.clear();
        }
    }
}
