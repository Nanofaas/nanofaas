package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 8 fix round 3 (issue #208), Item 3: {@link SchedulerEngine#remove(TicketId)} already had
 * coverage for "removal under the gate"; {@link SchedulerEngine#removeAllFor(String)} — the
 * generation-removal drain C2 added and the fix-round-2 Critical fixed by moving under the gate
 * — did not. {@code concurrentRemovalRacingAdmissionNeitherThrowsNorStrandsATicket}
 * (async-queue's {@code AsyncQueueConfigurationTest}) retires capacity before draining, which
 * stops the racing admitter almost immediately — so it exercises the *reorder* fix but never
 * puts real concurrent pressure on the store during the scan itself, and would pass unchanged
 * against a hypothetical regression that reordered capacity retirement correctly but put the
 * scan back outside the gate. This test exercises {@code removeAllFor} directly, with nothing
 * ever stopping the admitting thread, so the scan runs against a store under continuous,
 * unthrottled concurrent mutation — the shape that actually depends on the gate.
 */
class SchedulerEngineRemoveAllForGateDisciplineTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");
    private static final int PREPOPULATED_OTHER_FUNCTION_ENTRIES = 5_000;
    private static final int REMOVE_ALL_FOR_CALLS = 50;

    /**
     * Reliably passes against the gate-protected {@link SchedulerEngine#removeAllFor(String)}:
     * neither the admitting thread nor any {@code removeAllFor} call may throw, no matter how
     * much concurrent admission happens during the scan.
     *
     * <p>Verified RED during development against a temporarily off-gate variant of
     * {@code removeAllFor} (a raw {@code store.snapshotPending()} scan with no
     * {@code synchronized (gate)} around it, matching the shape the fix-round-2 Critical
     * describes) — that variant threw {@code ConcurrentModificationException} reliably under
     * this same test, every run. See the fix-round-3 report for the exact failure and the
     * revert/restore procedure.
     */
    @Test
    void removeAllForNeverRacesConcurrentAdmissionEvenUnderContinuousPressure() throws InterruptedException {
        PendingWorkStore store = new PendingWorkStore(50_000);
        EngineReadiness readiness = generation -> false; // nothing is ever selected/claimed
        EngineDispatch dispatch = mock(EngineDispatch.class);
        when(dispatch.isCurrent(any())).thenReturn(true);
        SchedulingStrategy strategy = mock(SchedulingStrategy.class);
        RecordingIndex index = new RecordingIndex();
        when(strategy.id()).thenReturn("test");
        when(strategy.newIndex()).thenReturn(index);
        SchedulerEngine engine = new SchedulerEngine(store, new StrategyRegistry(List.of(strategy)),
                "test", dispatch, readiness, generation -> true, Clock.fixed(NOW, java.time.ZoneOffset.UTC), () -> 0L);

        FunctionGeneration otherGeneration = new FunctionGeneration("other-fn", 1);
        for (int i = 0; i < PREPOPULATED_OTHER_FUNCTION_ENTRIES; i++) {
            assertThat(engine.enqueue(entry("other-fn-exec-" + i, otherGeneration))).isTrue();
        }

        FunctionGeneration echoGeneration = new FunctionGeneration("echo", 1);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> admitterFailure = new AtomicReference<>();
        AtomicInteger admitted = new AtomicInteger();
        Thread admitter = new Thread(() -> {
            int i = 0;
            while (running.get()) {
                try {
                    if (engine.enqueue(entry("echo-exec-" + i++, echoGeneration))) {
                        admitted.incrementAndGet();
                    }
                } catch (Throwable failure) {
                    admitterFailure.compareAndSet(null, failure);
                    return;
                }
            }
        });

        Throwable removerFailure = null;
        admitter.start();
        try {
            for (int call = 0; call < REMOVE_ALL_FOR_CALLS; call++) {
                try {
                    engine.removeAllFor("echo");
                } catch (Throwable failure) {
                    removerFailure = failure;
                    break;
                }
            }
        } finally {
            running.set(false);
            admitter.join(5_000);
        }

        assertThat(removerFailure).isNull();
        assertThat(admitterFailure.get()).isNull();
        // Sanity: the admitter actually raced (otherwise the test proves nothing about pressure).
        assertThat(admitted.get()).isPositive();
    }

    private static PendingEntry entry(String executionId, FunctionGeneration generation) {
        SchedulingTicket ticket = new SchedulingTicket(new TicketId(executionId, 1), generation,
                0, NOW, NOW, null);
        InvocationTask task = mock(InvocationTask.class);
        return new PendingEntry(ticket, task);
    }

    /** A minimal, hand-written {@link SchedulingIndex}: never selects anything (this test is
     * about the drain racing admission, not about dispatch). */
    private static final class RecordingIndex implements SchedulingIndex {
        private final Deque<SchedulingTicket> tickets = new ArrayDeque<>();

        @Override
        public void add(SchedulingTicket ticket) {
            tickets.addLast(ticket);
        }

        @Override
        public void remove(TicketId id) {
            tickets.removeIf(ticket -> ticket.id().equals(id));
        }

        @Override
        public SchedulingTicket select(Instant now, Predicate<FunctionGeneration> runnable) {
            return null;
        }

        @Override
        public void defer(TicketId id) {
            Iterator<SchedulingTicket> iterator = tickets.iterator();
            while (iterator.hasNext()) {
                SchedulingTicket ticket = iterator.next();
                if (ticket.id().equals(id)) {
                    iterator.remove();
                    tickets.addLast(ticket);
                    return;
                }
            }
        }

        @Override
        public int size() {
            return tickets.size();
        }

        @Override
        public void clear() {
            tickets.clear();
        }
    }
}
