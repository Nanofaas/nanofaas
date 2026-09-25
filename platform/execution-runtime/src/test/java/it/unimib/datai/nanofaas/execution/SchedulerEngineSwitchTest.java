package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationQuotaExceededException;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerSelection;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
 * Hot-swapping the active scheduling index while work is in flight: the rebuild of the
 * candidate from pending work, the single commit point, and every pre-commit failure path.
 *
 * <p>The two registered strategies produce indexes with deliberately opposite selection
 * orders (oldest-first vs newest-first), so "which policy is in charge" is observable in the
 * dispatch order rather than only in {@link SchedulerEngine#snapshot()}.
 *
 * <p>Tests drive {@link SchedulerEngine#tick()} and never start the worker thread, so the
 * frozen {@code nanoTime} used here can never hang a park.
 */
class SchedulerEngineSwitchTest {

    private final AtomicReference<Instant> clockNow = new AtomicReference<>();
    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");
    private static final FunctionGeneration ECHO = new FunctionGeneration("echo", 1);
    private static final FunctionGeneration MAIL = new FunctionGeneration("mail", 3);

    private final List<RecordingIndex> indexes = new ArrayList<>();
    private final List<String> submitted = new ArrayList<>();
    private final List<String> expired = new ArrayList<>();

    private SchedulingStrategy perFunction;
    private SchedulingStrategy sharedQueue;
    private EngineDispatch dispatch;
    private EngineReadiness readiness;
    private DispatchOwnership lease;
    private PendingWorkStore store;
    private SchedulerEngine engine;

    @BeforeEach
    void setUp() {
        perFunction = strategy("per-function", false);
        sharedQueue = strategy("shared-queue", true);
        lease = mock(DispatchOwnership.class);
        readiness = mock(EngineReadiness.class);
        when(readiness.runnable(any())).thenReturn(true);
        dispatch = mock(EngineDispatch.class);
        when(dispatch.tryAcquire(any())).thenReturn(lease);
        doAnswer(invocation -> submitted.add(task(invocation).executionId()))
                .when(dispatch).submit(any());
        doAnswer(invocation -> expired.add(task(invocation).executionId()))
                .when(dispatch).expired(any());
        store = new PendingWorkStore(64);
        engine = engineOver(store, () -> 0L);
    }

    private static InvocationTask task(org.mockito.invocation.InvocationOnMock invocation) {
        return invocation.getArgument(0);
    }

    private SchedulerEngine engineOver(PendingWorkStore pendingWorkStore, LongSupplier nanoTime) {
        clockNow.compareAndSet(null, NOW);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(call -> clockNow.get());
        return new SchedulerEngine(pendingWorkStore,
                new StrategyRegistry(List.of(perFunction, sharedQueue)), "per-function",
                dispatch, readiness, generation -> true, clock, nanoTime);
    }

    private SchedulingStrategy strategy(String id, boolean newestFirst) {
        SchedulingStrategy strategy = mock(SchedulingStrategy.class);
        when(strategy.id()).thenReturn(id);
        when(strategy.newIndex()).thenAnswer(invocation -> {
            RecordingIndex index = new RecordingIndex(id, newestFirst);
            indexes.add(index);
            return index;
        });
        return strategy;
    }

    private RecordingIndex activeIndex() {
        return indexes.get(indexes.size() - 1);
    }

    private SchedulingTicket admit(String executionId, long sequence) {
        return admit(engine, executionId, ECHO, sequence, NOW, null);
    }

    private SchedulingTicket admit(SchedulerEngine target, String executionId, FunctionGeneration generation,
                                   long sequence, Instant notBefore, Instant queueDeadline) {
        SchedulingTicket ticket = new SchedulingTicket(new TicketId(executionId, 1), generation,
                sequence, NOW, notBefore, queueDeadline);
        InvocationTask work = new InvocationTask(executionId, generation.functionName(), null, null,
                null, null, NOW, 1, InvocationKind.ASYNC);
        assertThat(target.enqueue(new PendingEntry(ticket, work))).isTrue();
        return ticket;
    }

    @Test
    void snapshotReportsTheActiveStrategyAndEveryRegisteredId() {
        assertThat(engine.snapshot())
                .isEqualTo(new SchedulerSelection("per-function",
                        List.of("per-function", "shared-queue")));

        engine.switchTo("shared-queue");

        assertThat(engine.snapshot().strategy()).isEqualTo("shared-queue");
        assertThat(engine.snapshot().available()).containsExactly("per-function", "shared-queue");
    }

    @Test
    void selectionMovesToTheNewPolicyInBothDirectionsWithoutLosingPendingWork() {
        admit("e1", 0);
        admit("e2", 1);
        admit("e3", 2);

        engine.tick();
        assertThat(submitted).containsExactly("e1");

        engine.switchTo("shared-queue");

        // Rebuilt in sequence order, then selected newest-first by the new policy.
        assertThat(activeIndex().ids()).containsExactly(new TicketId("e2", 1), new TicketId("e3", 1));
        engine.tick();
        assertThat(submitted).containsExactly("e1", "e3");

        engine.switchTo("per-function");

        engine.tick();
        assertThat(submitted).containsExactly("e1", "e3", "e2");
        assertThat(store.pendingCount()).isZero();
        assertThat(store.claimedCount()).isZero();
        assertThat(store.submittingCount()).isZero();
    }

    @Test
    void switchingToTheAlreadyActiveStrategyIsANoOp() {
        admit("e1", 0);
        RecordingIndex before = activeIndex();
        SchedulerSelection selection = engine.snapshot();

        engine.switchTo("per-function");

        assertThat(engine.snapshot()).isEqualTo(selection);
        assertThat(indexes).containsExactly(before);
        assertThat(before.cleared).isZero();
        assertThat(before.ids()).containsExactly(new TicketId("e1", 1));
        verify(perFunction, times(1)).newIndex();
    }

    @Test
    void anUnknownStrategyIsRefusedBeforeAnythingIsBuilt() {
        admit("e1", 0);
        SchedulerSelection before = engine.snapshot();

        assertThatThrownBy(() -> engine.switchTo("round-robin"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(engine.snapshot()).isEqualTo(before);
        assertThat(indexes).hasSize(1);
    }

    @Test
    void aFailingIndexFactoryLeavesThePreviousStrategyActive() {
        SchedulingTicket ticket = admit("e1", 0);
        RecordingIndex before = activeIndex();
        SchedulerSelection selection = engine.snapshot();
        doThrow(new IllegalStateException("injected build failure")).when(sharedQueue).newIndex();

        assertThatThrownBy(() -> engine.switchTo("shared-queue"))
                .isInstanceOf(SchedulerSwitchException.class)
                .hasRootCauseMessage("injected build failure")
                .extracting(failure -> ((SchedulerSwitchException) failure).reason())
                .isEqualTo(SchedulerSwitchException.Reason.PREPARATION);

        assertThat(engine.snapshot()).isEqualTo(selection);
        assertThat(store.get(ticket.id())).isNotNull();
        assertThat(before.cleared).isZero();
        engine.tick();
        assertThat(before.selects).isPositive();
        assertThat(submitted).containsExactly("e1");
    }

    @Test
    void aFailureAtTheKthAddAbortsTheSwitchAndClearsTheCandidate() {
        admit("e1", 0);
        admit("e2", 1);
        admit("e3", 2);
        RecordingIndex before = activeIndex();
        SchedulerSelection selection = engine.snapshot();
        // doAnswer, not when(...): re-stubbing through when() would run the setUp answer and
        // record a phantom index.
        doAnswer(invocation -> {
            RecordingIndex candidate = new RecordingIndex("shared-queue", true);
            candidate.failAtAdd = 3;
            indexes.add(candidate);
            return candidate;
        }).when(sharedQueue).newIndex();

        assertThatThrownBy(() -> engine.switchTo("shared-queue"))
                .isInstanceOf(SchedulerSwitchException.class)
                .hasRootCauseMessage("add #3 refused");

        RecordingIndex candidate = activeIndex();
        assertThat(candidate.added).isEqualTo(2);
        assertThat(candidate.cleared).isEqualTo(1);
        assertThat(candidate.size()).isZero();
        assertThat(engine.snapshot()).isEqualTo(selection);
        assertThat(before.ids()).hasSize(3);
        assertThat(store.pendingCount()).isEqualTo(3);
        engine.tick();
        assertThat(submitted).containsExactly("e1");
    }

    @Test
    void aPreparationThatOutrunsItsBudgetFailsBeforeTheCommit() {
        // Each read of the monotonic source jumps 30 ms: the deadline is set on the first read,
        // the first add still fits, the second read is past it.
        AtomicLong elapsed = new AtomicLong();
        SchedulerEngine timed = engineOver(store,
                () -> elapsed.getAndAdd(TimeUnit.MILLISECONDS.toNanos(30)));
        admit(timed, "e1", ECHO, 0, NOW, null);
        admit(timed, "e2", ECHO, 1, NOW, null);
        admit(timed, "e3", ECHO, 2, NOW, null);
        RecordingIndex before = activeIndex();

        assertThatThrownBy(() -> timed.switchTo("shared-queue"))
                .isInstanceOf(SchedulerSwitchException.class)
                .extracting(failure -> ((SchedulerSwitchException) failure).reason())
                .isEqualTo(SchedulerSwitchException.Reason.TIMEOUT);

        RecordingIndex candidate = activeIndex();
        assertThat(candidate.added).isEqualTo(1);
        assertThat(candidate.cleared).isEqualTo(1);
        assertThat(timed.snapshot().strategy()).isEqualTo("per-function");
        assertThat(before.ids()).hasSize(3);
        assertThat(store.pendingCount()).isEqualTo(3);
    }

    @Test
    void aPendingSetBeyondTheRebuildCapIsRefusedBeforeTheCommit() {
        int overCap = SchedulerEngine.MAX_SWITCH_REBUILD_TICKETS + 1;
        PendingWorkStore deep = new PendingWorkStore(overCap);
        SchedulerEngine deepEngine = engineOver(deep, () -> 0L);
        for (int i = 0; i < overCap; i++) {
            admit(deepEngine, "e" + i, ECHO, i, NOW, null);
        }
        RecordingIndex before = activeIndex();

        assertThatThrownBy(() -> deepEngine.switchTo("shared-queue"))
                .isInstanceOf(SchedulerSwitchException.class)
                .extracting(failure -> ((SchedulerSwitchException) failure).reason())
                .isEqualTo(SchedulerSwitchException.Reason.TEMPORARY_CAP);

        assertThat(activeIndex()).isSameAs(before);
        verify(sharedQueue, never()).newIndex();
        assertThat(deepEngine.snapshot().strategy()).isEqualTo("per-function");
        assertThat(before.size()).isEqualTo(overCap);
        assertThat(deep.pendingCount()).isEqualTo(overCap);
    }

    @Test
    void delayedWorkAndIdenticalQueueDeadlinesSurviveTheSwitch() {
        SchedulingTicket delayed = admit(engine, "late", ECHO, 0, NOW.plusSeconds(60), null);
        admit(engine, "d1", ECHO, 1, NOW, NOW.minusSeconds(1));
        admit(engine, "d2", MAIL, 2, NOW, NOW.minusSeconds(1));
        SchedulingTicket ready = admit(engine, "ready", ECHO, 3, NOW, null);

        engine.switchTo("shared-queue");

        RecordingIndex candidate = activeIndex();
        assertThat(candidate.ids()).containsExactly(new TicketId("d1", 1),
                new TicketId("d2", 1), ready.id());

        engine.tick();

        // The deadline bookkeeping is the engine's, not the index's: both tickets sharing the
        // same expired deadline are reaped, in sequence order, by the first pass after the switch.
        assertThat(expired).containsExactly("d1", "d2");
        // Newest-first, but the delayed ticket is not yet due, so the ready one is dispatched and
        // leaves; the delayed ticket keeps its future notBefore and stays.
        assertThat(submitted).containsExactly("ready");
        assertThat(candidate.ids()).isEmpty();
        assertThat(store.get(ready.id())).isNull();
        assertThat(store.pendingCount()).isEqualTo(1);
        assertThat(store.get(delayed.id())).isNotNull();
        clockNow.set(NOW.plusSeconds(60));
        engine.tick();
        engine.tick();
        assertThat(submitted).containsExactly("ready", "late");
        assertThat(store.reservedCount()).isZero();
    }

    @Test
    void aProvisionalClaimTakenBeforeTheSwitchIsReturnedToTheNewIndex() {
        SchedulingTicket claimed = admit("e1", 0);
        admit("e2", 1);
        AtomicBoolean switched = new AtomicBoolean();
        doAnswer(invocation -> {
            // Mid-carry, outside the gate, with the selection already claimed in the store.
            if (switched.compareAndSet(false, true)) {
                engine.switchTo("shared-queue");
            }
            return lease;
        }).when(dispatch).tryAcquire(any());

        engine.tick();

        RecordingIndex candidate = activeIndex();
        // The claim was excluded from the rebuild and put back by the carry path: exactly once.
        assertThat(candidate.ids()).containsExactly(new TicketId("e2", 1), claimed.id());
        assertThat(submitted).isEmpty();
        verify(lease).release();
        assertThat(store.claimedCount()).isZero();
        assertThat(store.pendingCount()).isEqualTo(2);

        engine.tick();

        assertThat(submitted).containsExactly("e1");
    }

    @Test
    void failedLeaseAfterSwitchReturnsClaimToNewIndex() {
        SchedulingTicket claimed = admit("e1", 0);
        doAnswer(invocation -> {
            engine.switchTo("shared-queue");
            return null;
        }).when(dispatch).tryAcquire(any());

        engine.tick();

        assertThat(store.claimedCount()).isZero();
        assertThat(store.pendingCount()).isEqualTo(1);
        assertThat(activeIndex().ids()).containsExactly(claimed.id());
        doReturn(lease).when(dispatch).tryAcquire(any());
        engine.signal();
        engine.tick();
        assertThat(submitted).containsExactly("e1");
    }

    @Test
    void throwingLeaseAcquisitionAfterSwitchReturnsClaimToNewIndex() {
        SchedulingTicket claimed = admit("e1", 0);
        doAnswer(invocation -> {
            engine.switchTo("shared-queue");
            throw new IllegalStateException("lease failed");
        }).when(dispatch).tryAcquire(any());

        assertThatThrownBy(engine::tick).isInstanceOf(IllegalStateException.class)
                .hasMessage("lease failed");

        assertThat(store.claimedCount()).isZero();
        assertThat(store.pendingCount()).isEqualTo(1);
        assertThat(activeIndex().ids()).containsExactly(claimed.id());
        doReturn(lease).when(dispatch).tryAcquire(any());
        engine.tick();
        assertThat(submitted).containsExactly("e1");
    }

    @Test
    void aSubmittingTicketDoesNotBlockTheSwitchAndRequeuesIntoTheNewIndex() {
        SchedulingTicket submitting = admit("e1", 0);
        admit("e2", 1);
        doAnswer(invocation -> {
            // Committed to dispatch: the reservation is held and the ticket is in no index.
            assertThat(store.submittingCount()).isEqualTo(1);
            engine.switchTo("shared-queue");
            throw new InvocationQuotaExceededException(InvocationQuotaExceededException.Resource.INPUT);
        }).when(dispatch).submit(any());

        engine.tick();

        RecordingIndex candidate = activeIndex();
        assertThat(candidate.ids()).containsExactly(new TicketId("e2", 1), submitting.id());
        assertThat(store.submittingCount()).isZero();
        assertThat(store.pendingCount()).isEqualTo(2);
        assertThat(store.get(submitting.id())).isNotNull();
        verify(dispatch, never()).rejected(any(), any());
    }

    @Test
    void aConcurrentEnqueueBelongsEitherBeforeOrAfterTheSwitch() throws Exception {
        admit("e1", 0);
        RecordingIndex before = activeIndex();
        SchedulingTicket late = new SchedulingTicket(new TicketId("e2", 1), ECHO, 1, NOW, NOW, null);
        InvocationTask lateTask = new InvocationTask("e2", "echo", null, null, null, null,
                NOW, 1, InvocationKind.ASYNC);
        List<Thread> admitters = new ArrayList<>();
        doAnswer(invocation -> {
            RecordingIndex candidate = new RecordingIndex("shared-queue", true);
            candidate.onFirstAdd = () -> {
                // The gate is held for the whole rebuild, so this thread cannot interleave with
                // it: whatever it admits belongs entirely after the commit.
                Thread admitter = new Thread(() -> engine.enqueue(new PendingEntry(late, lateTask)));
                admitters.add(admitter);
                admitter.start();
            };
            indexes.add(candidate);
            return candidate;
        }).when(sharedQueue).newIndex();

        engine.switchTo("shared-queue");
        for (Thread admitter : admitters) {
            admitter.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(admitter.isAlive()).isFalse();
        }

        RecordingIndex candidate = activeIndex();
        // Had the enqueue interleaved with the rebuild it would have landed in the index that
        // was still active — the one discarded and cleared at the commit — so the ticket would
        // hold a reservation that no index can select. Both assertions together rule that out.
        assertThat(candidate.ids()).containsExactly(new TicketId("e1", 1), late.id());
        assertThat(before.ids()).isEmpty();
        assertThat(store.pendingCount()).isEqualTo(2);
    }

    @Test
    void repeatedSwitchesLeaveExactlyOneLiveIndex() {
        admit("e1", 0);
        admit("e2", 1);

        engine.switchTo("shared-queue");
        engine.switchTo("per-function");
        engine.switchTo("shared-queue");

        assertThat(indexes).hasSize(4);
        for (RecordingIndex superseded : indexes.subList(0, 3)) {
            assertThat(superseded.cleared).isEqualTo(1);
            assertThat(superseded.size()).isZero();
        }
        RecordingIndex live = activeIndex();
        assertThat(live.cleared).isZero();
        assertThat(live.ids()).containsExactly(new TicketId("e1", 1), new TicketId("e2", 1));
        assertThat(store.pendingCount()).isEqualTo(2);

        engine.tick();
        assertThat(submitted).containsExactly("e2");
    }

    @Test
    void failedPreparationPreservesMixedReadyAndDelayedMembership() {
        SchedulingTicket delayed = admit(engine, "late", ECHO, 0, NOW.plusSeconds(1), null);
        admit("ready", 1);
        RecordingIndex before = activeIndex();
        doAnswer(call -> {
            RecordingIndex candidate = new RecordingIndex("shared-queue", true);
            candidate.failAtAdd = 1;
            indexes.add(candidate);
            return candidate;
        }).when(sharedQueue).newIndex();
        assertThatThrownBy(() -> engine.switchTo("shared-queue"))
                .isInstanceOf(SchedulerSwitchException.class);
        assertThat(before.ids()).containsExactly(new TicketId("ready", 1));
        assertThat(activeIndex().ids()).isEmpty();
        engine.tick();
        assertThat(submitted).containsExactly("ready");
        assertThat(store.get(delayed.id()).ticket().notBefore()).isEqualTo(NOW.plusSeconds(1));
        clockNow.set(NOW.plusSeconds(1));
        engine.tick();
        engine.tick();
        assertThat(submitted).containsExactly("ready", "late");
        assertThat(store.reservedCount()).isZero();
    }

    @Test
    void delayedMembershipSurvivesClockAdvanceDuringCandidateBuild() {
        admit("ready", 0);
        admit(engine, "late", ECHO, 1, NOW.plusSeconds(1), null);
        doAnswer(call -> {
            RecordingIndex candidate = new RecordingIndex("shared-queue", true);
            candidate.onFirstAdd = () -> clockNow.set(NOW.plusSeconds(1));
            indexes.add(candidate);
            return candidate;
        }).when(sharedQueue).newIndex();
        engine.switchTo("shared-queue");
        assertThat(activeIndex().ids()).containsExactly(new TicketId("ready", 1));
        engine.tick();
        engine.tick();
        engine.tick();
        assertThat(submitted).containsExactly("late", "ready");
        assertThat(store.reservedCount()).isZero();
    }

    @Test
    void switchDuringClaimWithBackwardClockKeepsOriginalDueInstant() {
        SchedulingTicket ticket = admit("claimed", 0);
        doAnswer(call -> {
            engine.switchTo("shared-queue");
            clockNow.set(NOW.minusSeconds(1));
            return lease;
        }).when(dispatch).tryAcquire(any());
        engine.tick();
        assertThat(activeIndex().ids()).isEmpty();
        assertThat(submitted).isEmpty();
        verify(lease).release();
        assertThat(store.get(ticket.id()).ticket().notBefore()).isEqualTo(NOW);
        doReturn(lease).when(dispatch).tryAcquire(any());
        clockNow.set(NOW);
        engine.tick();
        engine.tick();
        assertThat(submitted).containsExactly("claimed");
        assertThat(store.reservedCount()).isZero();
    }

    @Test
    void delayedBacklogCountsTowardRebuildCapBeforeSnapshot() {
        int count = SchedulerEngine.MAX_SWITCH_REBUILD_TICKETS + 1;
        PendingWorkStore deep = new PendingWorkStore(count);
        SchedulerEngine deepEngine = engineOver(deep, () -> 0L);
        for (int i = 0; i < count; i++) {
            admit(deepEngine, "late" + i, ECHO, i, Instant.MAX, null);
        }
        assertThatThrownBy(() -> deepEngine.switchTo("shared-queue"))
                .isInstanceOf(SchedulerSwitchException.class)
                .extracting(failure -> ((SchedulerSwitchException) failure).reason())
                .isEqualTo(SchedulerSwitchException.Reason.TEMPORARY_CAP);
        verify(sharedQueue, never()).newIndex();
        assertThat(activeIndex().ids()).isEmpty();
        assertThat(deep.reservedCount()).isEqualTo(count);
    }

    /**
     * A minimal index: insertion-ordered, honouring {@code notBefore} and the engine's runnable
     * predicate, selecting either oldest-first or newest-first so the two registered strategies
     * are distinguishable by dispatch order alone. It records what a switch does to it.
     */
    private static final class RecordingIndex implements SchedulingIndex {

        private final String owner;
        private final boolean newestFirst;
        private final Deque<SchedulingTicket> queue = new ArrayDeque<>();
        private final Set<TicketId> present = new HashSet<>();

        private int attempts;
        private int added;
        private int cleared;
        private int selects;
        private int failAtAdd;
        private Runnable onFirstAdd;

        private RecordingIndex(String owner, boolean newestFirst) {
            this.owner = owner;
            this.newestFirst = newestFirst;
        }

        @Override
        public void add(SchedulingTicket ticket) {
            if (onFirstAdd != null) {
                Runnable hook = onFirstAdd;
                onFirstAdd = null;
                hook.run();
            }
            attempts++;
            if (attempts == failAtAdd) {
                throw new IllegalStateException("add #" + attempts + " refused");
            }
            if (!present.add(ticket.id())) {
                throw new IllegalArgumentException("duplicate ticket id: " + ticket.id());
            }
            queue.addLast(ticket);
            added++;
        }

        @Override
        public void remove(TicketId id) {
            if (present.remove(id)) {
                queue.removeIf(ticket -> ticket.id().equals(id));
            }
        }

        @Override
        public SchedulingTicket select(Instant now, Predicate<FunctionGeneration> runnable) {
            selects++;
            List<SchedulingTicket> order = new ArrayList<>(queue);
            if (newestFirst) {
                order = order.reversed();
            }
            for (SchedulingTicket ticket : order) {
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
            cleared++;
            queue.clear();
            present.clear();
        }

        List<TicketId> ids() {
            return queue.stream().map(SchedulingTicket::id).toList();
        }

        @Override
        public String toString() {
            return owner + "#" + System.identityHashCode(this);
        }
    }
}
