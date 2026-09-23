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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression for the NPE precondition Task 8 makes reachable: {@code SchedulerEngine.deadlines}
 * is a {@code TreeSet<SchedulingTicket>} ordered by {@code SchedulingTicket::queueDeadline},
 * which is nullable by contract (the async/function-queue profile always uses {@code null}).
 * {@code TreeSet.remove} on a NON-EMPTY set invokes the comparator, which NPEs on a null
 * deadline; an EMPTY set short-circuits before comparing, which is the only reason this was
 * unreachable while every deployed profile was uniform (all tickets null-deadline, or all
 * non-null). Composing both admission fronts onto one engine (this task) is exactly the mixed
 * case: a sync-origin ticket with a real deadline keeps {@code deadlines} non-empty while a
 * function-queue-origin ticket with a null deadline passes through it.
 *
 * <p>Each test below keeps one non-null-deadline ticket ("kept") untouched in the index — so
 * {@code deadlines} is non-empty throughout — while a null-deadline ticket ("target") is driven
 * through one of the three call sites that were unguarded before this task's fix:
 * {@code selectAndClaim}'s orphan-drop, {@code requeue}'s cancel branch, and
 * {@code finishSubmit}. All three now guard with {@code if (ticket.queueDeadline() != null)},
 * matching {@code retire()}'s pre-existing guard.
 */
class SchedulerEngineDeadlineGuardRegressionTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    private final FunctionGeneration keptGeneration = new FunctionGeneration("kept-fn", 1);
    private final FunctionGeneration targetGeneration = new FunctionGeneration("target-fn", 1);

    private final SchedulingTicket keptTicket = new SchedulingTicket(
            new TicketId("kept-exec", 1), keptGeneration, 0, NOW, NOW, NOW.plus(Duration.ofSeconds(30)));
    private final SchedulingTicket targetTicket = new SchedulingTicket(
            new TicketId("target-exec", 1), targetGeneration, 1, NOW, NOW, null);

    private RecordingIndex index;
    private EngineDispatch dispatch;
    private EngineReadiness readiness;
    private PendingWorkStore store;
    private SchedulerEngine engine;
    private InvocationTask targetTask;
    private InvocationTask leasedTargetTask;
    private DispatchOwnership targetLease;

    @BeforeEach
    void setUp() {
        index = new RecordingIndex();
        dispatch = mock(EngineDispatch.class);
        readiness = mock(EngineReadiness.class);
        // The "kept" generation never looks runnable: its ticket is skipped by every selection,
        // stays pending and keeps the deadline set non-empty for the whole test.
        when(readiness.runnable(keptGeneration)).thenReturn(false);
        when(readiness.runnable(targetGeneration)).thenReturn(true);

        targetTask = mock(InvocationTask.class);
        when(targetTask.functionName()).thenReturn(targetGeneration.functionName());
        leasedTargetTask = mock(InvocationTask.class);
        when(targetTask.withDispatchLease(any())).thenReturn(leasedTargetTask);
        targetLease = mock(DispatchOwnership.class);
        when(dispatch.tryAcquire(targetTicket)).thenReturn(targetLease);

        SchedulingStrategy strategy = mock(SchedulingStrategy.class);
        when(strategy.id()).thenReturn("test");
        when(strategy.newIndex()).thenReturn(index);
        store = new PendingWorkStore(8);
        engine = new SchedulerEngine(store, new StrategyRegistry(List.of(strategy)), "test",
                dispatch, readiness, generation -> true, Clock.fixed(NOW, ZoneOffset.UTC), () -> 0L);

        // Keeps `deadlines` non-empty for the whole test: a real, distinct deadline, never
        // selected because its generation is never runnable.
        engine.enqueue(new PendingEntry(keptTicket, mock(InvocationTask.class)));
    }

    @Test
    void finishSubmitOnANullDeadlineTicketDoesNotThrowWhileDeadlinesIsNonEmpty() {
        engine.enqueue(new PendingEntry(targetTicket, targetTask));

        // Normal successful dispatch: the target ticket is selected, claimed, committed and
        // handed to submit(), which settles through finishSubmit() — the third unguarded site.
        assertThatCode(engine::tick).doesNotThrowAnyException();

        assertThatCode(() -> org.mockito.Mockito.verify(dispatch).submit(leasedTargetTask))
                .doesNotThrowAnyException();
    }

    @Test
    void requeueCancelBranchOnANullDeadlineTicketDoesNotThrowWhileDeadlinesIsNonEmpty() {
        engine.enqueue(new PendingEntry(targetTicket, targetTask));

        // Input backpressure drives the requeue path; requeue() itself calls engine.remove() on
        // the same (still-submitting) ticket first, which — because the ticket is mid-submit —
        // only records a cancel request. requeue()'s cancel branch then applies it, hitting the
        // second unguarded site.
        doAnswer(invocation -> {
            engine.remove(targetTicket.id());
            throw new InvocationQuotaExceededException(InvocationQuotaExceededException.Resource.INPUT);
        }).when(dispatch).submit(leasedTargetTask);

        assertThatCode(engine::tick).doesNotThrowAnyException();
        assertThatCode(() -> org.mockito.Mockito.verify(dispatch).removed(targetTask))
                .doesNotThrowAnyException();
    }

    @Test
    void orphanDropOnANullDeadlineTicketDoesNotThrowWhileDeadlinesIsNonEmpty() {
        // The index holds the ticket but the store never admitted it (the "index outlived its
        // entry" case selectAndClaim's orphan-drop branch exists for): added directly to the
        // index, bypassing engine.enqueue, which is exactly the desync that branch handles.
        index.add(targetTicket);

        assertThatCode(engine::tick).doesNotThrowAnyException();
        // Not throwing is not the whole claim: the branch must have RUN, i.e. the orphan must have
        // left the index. setUp's "kept" ticket is still there — its generation is never runnable,
        // so no pass selects it — which is why the surviving size is one and not zero.
        assertThat(index.size()).isEqualTo(1);
    }

    /**
     * A minimal, hand-written {@link SchedulingIndex}: one FIFO in admission order, filtered by
     * the engine's {@code runnable} predicate at selection time. Not a strategy under test —
     * both real strategies already have their own coverage — just enough behavior to control
     * exactly which ticket a pass selects.
     */
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
            for (SchedulingTicket ticket : tickets) {
                if (!ticket.notBefore().isAfter(now) && runnable.test(ticket.generation())) {
                    return ticket;
                }
            }
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
