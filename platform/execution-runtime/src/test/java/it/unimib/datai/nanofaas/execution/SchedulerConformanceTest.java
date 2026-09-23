package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationQuotaExceededException;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import it.unimib.datai.nanofaas.modules.asyncqueue.PerFunctionSchedulingStrategy;
import it.unimib.datai.nanofaas.modules.syncqueue.SharedQueueSchedulingStrategy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Common conformance suite for both scheduling strategies (issue #208, Task 12, spec section
 * 10). The two families of tests below exercise the same contract against
 * {@code PerFunctionSchedulingStrategy} and {@code SharedQueueSchedulingStrategy} — the real
 * classes, not test doubles, per the brief:
 *
 * <ul>
 *   <li>{@code SchedulingIndex}-level tests, which check the pure add/remove/select/defer
 *       contract directly, independent of the engine.</li>
 *   <li>Engine-level tests, which wire a real {@link SchedulerEngine} over one real strategy at
 *       a time and check invariants that must hold regardless of which policy is active: no
 *       ticket lost or dispatched twice, resources released symmetrically with admission,
 *       deadlines enforced, generation removal scoped correctly, and one blocked function not
 *       starving a runnable one.</li>
 * </ul>
 *
 * <p>This suite does not re-litigate every row of section 10's table: claim/dispatch races
 * against a hostile index, lifecycle-callout failures and switch mechanics already have
 * dedicated coverage in {@code SchedulerEngineDispatchTest}, {@code SchedulerEngineSwitchTest}
 * and (for the barrier-forced interleavings) {@code SchedulerSwitchRaceTest}; this file's job is
 * specifically to run the shared contract against both real strategies, not to duplicate those.
 */
class SchedulerConformanceTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    static java.util.stream.Stream<Arguments> strategies() {
        return java.util.stream.Stream.of(
                Arguments.of(new PerFunctionSchedulingStrategy()),
                Arguments.of(new SharedQueueSchedulingStrategy()));
    }

    // ------------------------------------------------------------------
    // SchedulingIndex-level conformance
    // ------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("strategies")
    void removingATicketMakesItUnselectable(SchedulingStrategy strategy) {
        var index = strategy.newIndex();
        var ticket = new SchedulingTicket(new TicketId("one", 1),
                new FunctionGeneration("echo", 1), 1, NOW, NOW, null);
        index.add(ticket);
        index.remove(ticket.id());
        assertThat(index.select(NOW, generation -> true)).isNull();
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void removingAnAbsentTicketIsANoOp(SchedulingStrategy strategy) {
        var index = strategy.newIndex();
        index.remove(new TicketId("never-added", 1));
        assertThat(index.size()).isZero();
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void duplicateAddIsRejectedAndTheOriginalTicketSurvives(SchedulingStrategy strategy) {
        var index = strategy.newIndex();
        var ticket = new SchedulingTicket(new TicketId("dup", 1),
                new FunctionGeneration("echo", 1), 1, NOW, NOW, null);
        index.add(ticket);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> index.add(
                        new SchedulingTicket(ticket.id(), ticket.generation(), 2, NOW, NOW, null)))
                .isInstanceOf(RuntimeException.class);

        assertThat(index.size()).isEqualTo(1);
        assertThat(index.select(NOW, g -> true)).isEqualTo(ticket);
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void selectIsAPureScanAndDoesNotMutateTheIndex(SchedulingStrategy strategy) {
        var index = strategy.newIndex();
        var ticket = new SchedulingTicket(new TicketId("one", 1),
                new FunctionGeneration("echo", 1), 1, NOW, NOW, null);
        index.add(ticket);

        SchedulingTicket first = index.select(NOW, g -> true);
        SchedulingTicket second = index.select(NOW, g -> true);

        assertThat(first).isEqualTo(ticket);
        assertThat(second).isEqualTo(ticket);
        assertThat(index.size()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void aTicketNotYetDueIsNotSelectable(SchedulingStrategy strategy) {
        var index = strategy.newIndex();
        var future = new SchedulingTicket(new TicketId("future", 1),
                new FunctionGeneration("echo", 1), 1, NOW, NOW.plusSeconds(60), null);
        index.add(future);
        assertThat(index.select(NOW, g -> true)).isNull();
        assertThat(index.select(NOW.plusSeconds(61), g -> true)).isEqualTo(future);
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void deferAllowsProgressWithoutLosingTheDeferredTicket(SchedulingStrategy strategy) {
        var index = strategy.newIndex();
        FunctionGeneration echo = new FunctionGeneration("echo", 1);
        var blocked = new SchedulingTicket(new TicketId("blocked", 1), echo, 0, NOW, NOW, null);
        var runnable = new SchedulingTicket(new TicketId("runnable", 1), echo, 1, NOW, NOW, null);
        index.add(blocked);
        index.add(runnable);

        // The engine's own protocol on a claim it cannot dispatch: defer, then re-scan.
        SchedulingTicket selected = index.select(NOW, g -> true);
        assertThat(selected).isIn(blocked, runnable);
        index.defer(selected.id());

        // Whichever ticket was deferred, both are still present and at least one is selectable —
        // a hostile defer must never make every ticket permanently unselectable.
        assertThat(index.size()).isEqualTo(2);
        assertThat(index.select(NOW, g -> true)).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void clearRemovesEveryTicketAndBookkeeping(SchedulingStrategy strategy) {
        var index = strategy.newIndex();
        FunctionGeneration echo = new FunctionGeneration("echo", 1);
        index.add(new SchedulingTicket(new TicketId("a", 1), echo, 0, NOW, NOW, null));
        index.add(new SchedulingTicket(new TicketId("b", 1), echo, 1, NOW, NOW, null));

        index.clear();

        assertThat(index.size()).isZero();
        assertThat(index.select(NOW, g -> true)).isNull();
        // A ticket id used before clear() may be re-admitted afterwards without tripping the
        // duplicate-id guard: clear() must reset identity bookkeeping, not just the queue.
        index.add(new SchedulingTicket(new TicketId("a", 1), echo, 0, NOW, NOW, null));
        assertThat(index.size()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void aGenerationTheRunnablePredicateRejectsIsNeverSelected(SchedulingStrategy strategy) {
        var index = strategy.newIndex();
        FunctionGeneration blockedGen = new FunctionGeneration("blocked-fn", 1);
        FunctionGeneration okGen = new FunctionGeneration("ok-fn", 1);
        var blockedTicket = new SchedulingTicket(new TicketId("b", 1), blockedGen, 0, NOW, NOW, null);
        var okTicket = new SchedulingTicket(new TicketId("ok", 1), okGen, 1, NOW, NOW, null);
        index.add(blockedTicket);
        index.add(okTicket);

        SchedulingTicket selected = index.select(NOW, g -> !g.equals(blockedGen));

        assertThat(selected).isEqualTo(okTicket);
    }

    // ------------------------------------------------------------------
    // Engine-level conformance: same invariants, real engine, one real strategy at a time
    // ------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("strategies")
    void syncAndAsyncOnTheSameFunctionBothDispatchWithoutLoss(SchedulingStrategy strategy) {
        Fixture f = new Fixture(strategy);
        f.admit("sync-1", InvocationKind.SYNC, f.echo, 0);
        f.admit("async-1", InvocationKind.ASYNC, f.echo, 1);

        f.engine.tick();
        f.engine.tick();

        assertThat(f.submitted).containsExactlyInAnyOrder("sync-1", "async-1");
        assertThat(f.store.pendingCount()).isZero();
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void admissionIsRefusedOnceTheStoreIsFullAndNothingIsLostOrDuplicated(SchedulingStrategy strategy) {
        PendingWorkStore tiny = new PendingWorkStore(1);
        Fixture f = new Fixture(strategy, tiny);
        assertThat(f.tryAdmit("e1", InvocationKind.ASYNC, f.echo, 0)).isTrue();
        assertThat(f.tryAdmit("e2", InvocationKind.ASYNC, f.echo, 1)).isFalse();

        f.engine.tick();

        assertThat(f.submitted).containsExactly("e1");
        assertThat(tiny.pendingCount()).isZero();
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void aTicketIsNeverSubmittedTwiceForTheSameAttemptWhenCapacityIsDeniedThenGranted(
            SchedulingStrategy strategy) {
        Fixture f = new Fixture(strategy);
        f.admit("e1", InvocationKind.ASYNC, f.echo, 0);
        AtomicInteger acquireCalls = new AtomicInteger();
        when(f.dispatch.tryAcquire(any())).thenAnswer(invocation -> {
            // First attempt denied (capacity momentarily exhausted), second attempt granted —
            // mirrors "capacity changes after selection" (spec section 10, Claim/dispatch row).
            return acquireCalls.getAndIncrement() == 0 ? null : f.lease;
        });

        f.engine.tick(); // denied: deferred, reservation kept
        f.engine.signal();
        f.engine.tick(); // granted: dispatched exactly once

        assertThat(f.submitted).containsExactly("e1");
        assertThat(f.store.pendingCount()).isZero();
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void aTicketPastItsQueueDeadlineExpiresExactlyOnceAndIsNeverDispatched(SchedulingStrategy strategy) {
        Fixture f = new Fixture(strategy);
        f.admit("expired", InvocationKind.ASYNC, f.echo, 0, NOW, NOW.minusSeconds(1));

        f.engine.tick();

        assertThat(f.expired).containsExactly("expired");
        assertThat(f.submitted).isEmpty();
        assertThat(f.store.pendingCount()).isZero();
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void removeAllForOnlyDropsTicketsOfThatFunctionsGeneration(SchedulingStrategy strategy) {
        Fixture f = new Fixture(strategy);
        FunctionGeneration mail = new FunctionGeneration("mail", 1);
        f.admit("echo-1", InvocationKind.ASYNC, f.echo, 0);
        f.admitFor(mail, "mail-1", InvocationKind.ASYNC, 1);

        f.engine.removeAllFor("echo");

        assertThat(f.removed).containsExactly("echo-1");
        f.engine.tick();
        assertThat(f.submitted).containsExactly("mail-1");
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void oneBlockedFunctionDoesNotStarveARunnableOne(SchedulingStrategy strategy) {
        Fixture f = new Fixture(strategy);
        FunctionGeneration blockedFn = new FunctionGeneration("blocked-fn", 1);
        when(f.readiness.runnable(blockedFn)).thenReturn(false);
        f.admitFor(blockedFn, "blocked-1", InvocationKind.ASYNC, 0);
        f.admit("ok-1", InvocationKind.ASYNC, f.echo, 1);

        f.engine.tick();
        f.engine.tick();

        assertThat(f.submitted).containsExactly("ok-1");
        assertThat(f.store.pendingCount()).isEqualTo(1);
    }

    static java.util.stream.Stream<Arguments> scanWindowCases() {
        return java.util.stream.Stream.of(
                Arguments.of(new PerFunctionSchedulingStrategy(), 63),
                Arguments.of(new PerFunctionSchedulingStrategy(), 64),
                Arguments.of(new SharedQueueSchedulingStrategy(), 63),
                Arguments.of(new SharedQueueSchedulingStrategy(), 64));
    }

    @ParameterizedTest(name = "{0}, blocked tickets = {1}")
    @MethodSource("scanWindowCases")
    void runnableWorkBeyondBlockedTicketsEventuallyDispatches(SchedulingStrategy strategy, int blockedCount) {
        Fixture f = new Fixture(strategy);
        FunctionGeneration blocked = new FunctionGeneration("blocked", 1);
        when(f.readiness.runnable(blocked)).thenReturn(false);
        for (int i = 0; i < blockedCount; i++) {
            f.admitFor(blocked, "blocked-" + i, InvocationKind.ASYNC, i);
        }
        f.admit("ready", InvocationKind.ASYNC, f.echo, blockedCount);

        // Time and capacity stay fixed: progress must come from advancing the scan window.
        for (int i = 0; i < 100; i++) {
            f.engine.signal();
            f.engine.tick();
        }

        assertAll(
                () -> assertThat(f.submitted).containsExactly("ready"),
                () -> assertThat(f.engine.reservedCount("echo")).isZero(),
                () -> assertThat(f.engine.reservedCount("blocked")).isEqualTo(blockedCount));
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void functionRemovalWhileAcquiringLeaseSettlesTheClaim(SchedulingStrategy strategy) {
        Fixture f = new Fixture(strategy);
        SchedulingTicket ticket = f.admit("claimed", InvocationKind.ASYNC, f.echo, 0);
        doAnswer(invocation -> {
            assertThat(f.store.claimedCount()).isEqualTo(1);
            when(f.readiness.runnable(f.echo)).thenReturn(false);
            f.engine.removeAllFor("echo");
            return null; // Capacity retired before this acquisition could finish.
        }).when(f.dispatch).tryAcquire(any());

        f.engine.tick();

        assertAll(
                () -> assertThat(f.removed).containsExactly("claimed"),
                () -> assertThat(f.store.get(ticket.id())).isNull(),
                () -> assertThat(f.engine.reservedCount("echo")).isZero(),
                () -> assertThat(f.store.claimedCount()).isZero(),
                () -> assertThat(f.submitted).isEmpty());
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void functionRemovalDuringSubmitCancelsAnInputBackpressureRequeue(SchedulingStrategy strategy) {
        Fixture f = new Fixture(strategy);
        SchedulingTicket ticket = f.admit("submitting", InvocationKind.ASYNC, f.echo, 0);
        doAnswer(invocation -> {
            assertThat(f.store.submittingCount()).isEqualTo(1);
            when(f.readiness.runnable(f.echo)).thenReturn(false);
            f.engine.removeAllFor("echo");
            throw new InvocationQuotaExceededException(InvocationQuotaExceededException.Resource.INPUT);
        }).when(f.dispatch).submit(any());

        f.engine.tick();

        assertAll(
                () -> assertThat(f.removed).containsExactly("submitting"),
                () -> assertThat(f.store.get(ticket.id())).isNull(),
                () -> assertThat(f.engine.reservedCount("echo")).isZero(),
                () -> assertThat(f.store.submittingCount()).isZero());
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void removingAPendingTicketReleasesItsReservationSymmetricallyWithAdmission(SchedulingStrategy strategy) {
        Fixture f = new Fixture(strategy);
        SchedulingTicket ticket = f.admit("e1", InvocationKind.ASYNC, f.echo, 0);
        assertThat(f.engine.reservedCount("echo")).isEqualTo(1);

        f.engine.remove(ticket.id());

        assertThat(f.engine.reservedCount("echo")).isZero();
        assertThat(f.removed).containsExactly("e1");
        f.engine.tick();
        assertThat(f.submitted).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void switchingToTheSameStrategyIsANoOpAndKeepsEveryPendingTicket(SchedulingStrategy strategy) {
        Fixture f = new Fixture(strategy);
        f.admit("e1", InvocationKind.ASYNC, f.echo, 0);
        f.admit("e2", InvocationKind.ASYNC, f.echo, 1);

        f.engine.switchTo(strategy.id());

        assertThat(f.engine.snapshot().strategy()).isEqualTo(strategy.id());
        assertThat(f.store.pendingCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // Per-function cap enforced by engine admission
    // ------------------------------------------------------------------

    private static PendingEntry candidate(String id, FunctionGeneration generation, long sequence) {
        var ticket = new SchedulingTicket(new TicketId(id, 1), generation,
                sequence, NOW, NOW, null);
        var task = new InvocationTask(id, generation.functionName(), null, null,
                null, null, NOW, 1, InvocationKind.ASYNC);
        return new PendingEntry(ticket, task);
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void capIncludesClaimsAndSubmitsUntilTheReservationEnds(SchedulingStrategy strategy) {
        Fixture f = new Fixture(strategy);
        var first = candidate("first", f.echo, 0);
        assertThat(f.engine.enqueue(first, 1)).isTrue();
        assertThat(f.engine.isQueueFull("echo")).isTrue();
        doAnswer(invocation -> {
            assertThat(f.engine.enqueue(candidate("during-submit", f.echo, 1), 1)).isFalse();
            return null;
        }).when(f.dispatch).submit(any());
        f.engine.tick();
        assertThat(f.engine.isQueueFull("echo")).isFalse();
        assertThat(f.engine.enqueue(candidate("after-submit", f.echo, 2), 1)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void concurrentAdmissionsCannotBothTakeTheLastSlot(SchedulingStrategy strategy) throws Exception {
        Fixture f = new Fixture(strategy);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("start");
                return f.engine.enqueue(candidate("a", f.echo, 0), 1);
            });
            var b = pool.submit(() -> {
                if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("start");
                return f.engine.enqueue(candidate("b", f.echo, 1), 1);
            });
            start.countDown();
            assertThat(java.util.List.of(a.get(5, java.util.concurrent.TimeUnit.SECONDS),
                    b.get(5, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(f.engine.reservedCount("echo")).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void removalDoesNotEraseTheOccupancyOfAnOldSubmit(SchedulingStrategy strategy) {
        Fixture f = new Fixture(strategy);
        var next = new FunctionGeneration("echo", 2);
        f.engine.enqueue(candidate("old", f.echo, 0), 1);
        doAnswer(invocation -> {
            f.engine.removeAllFor("echo");
            assertThat(f.engine.enqueue(candidate("new-too-early", next, 1), 1)).isFalse();
            return null;
        }).when(f.dispatch).submit(any());
        f.engine.tick();
        assertThat(f.engine.enqueue(candidate("new", next, 2), 1)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void capUpdatesAndRemovalAreVisibleToTheAdvisoryCheck(SchedulingStrategy strategy) {
        Fixture f = new Fixture(strategy);
        assertThat(f.engine.isQueueFull("echo")).isFalse();
        assertThat(f.engine.enqueue(candidate("a", f.echo, 0), 2)).isTrue();
        assertThat(f.engine.enqueue(candidate("b", f.echo, 1), 1)).isFalse();
        assertThat(f.engine.isQueueFull("echo")).isTrue();
        f.engine.removeAllFor("echo");
        assertThat(f.engine.isQueueFull("echo")).isFalse();
        assertThat(f.engine.enqueue(candidate("c", f.echo, 2), 1)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void advisoryQueueFullCheckDoesNotWaitForTheEngineGate(SchedulingStrategy strategy) throws Exception {
        Fixture f = new Fixture(strategy);
        var insideGate = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        // readiness runs under the gate during selection: parking it there holds the gate.
        when(f.readiness.runnable(f.echo)).thenAnswer(invocation -> {
            insideGate.countDown();
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return false;
        });
        assertThat(f.engine.enqueue(candidate("a", f.echo, 0), 1)).isTrue();
        Thread worker = new Thread(f.engine::tick);
        worker.start();
        try {
            assertThat(insideGate.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var check = java.util.concurrent.CompletableFuture.supplyAsync(() -> f.engine.isQueueFull("echo"));
            assertThat(check.get(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            worker.join(5_000);
        }
    }

    // ------------------------------------------------------------------
    // Generation validation during admission
    // ------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("strategies")
    void staleAdmissionNeverBecomesAnEngineReservation(SchedulingStrategy strategy) {
        Fixture f = new Fixture(strategy);
        when(f.generationActive.test(f.echo)).thenReturn(false);
        assertThat(f.engine.enqueue(candidate("stale", f.echo, 0), 1)).isFalse();
        assertThat(f.store.reservedCount()).isZero();
        assertThat(f.store.get(new TicketId("stale", 1))).isNull();
        assertThat(f.engine.isQueueFull("echo")).isFalse();
        assertThat(f.removed).isEmpty();
        assertThat(f.submitted).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void capacityBlockedButLiveGenerationCanQueue(SchedulingStrategy strategy) {
        Fixture f = new Fixture(strategy);
        when(f.readiness.runnable(f.echo)).thenReturn(false);
        assertThat(f.engine.enqueue(candidate("waiting", f.echo, 0), 1)).isTrue();
        f.engine.tick();
        assertThat(f.engine.reservedCount("echo")).isEqualTo(1);
        assertThat(f.submitted).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void anOldGenerationCannotEnterAfterTheNewOneIsRegistered(SchedulingStrategy strategy) {
        Fixture f = new Fixture(strategy);
        var current = new FunctionGeneration("echo", 2);
        when(f.generationActive.test(f.echo)).thenReturn(false);
        when(f.generationActive.test(current)).thenReturn(true);
        assertThat(f.engine.enqueue(candidate("old", f.echo, 0), 1)).isFalse();
        assertThat(f.engine.enqueue(candidate("new", current, 1), 1)).isTrue();
        assertThat(f.store.reservedCount()).isEqualTo(1);
    }

    /**
     * Shared fixture: a real {@link SchedulerEngine} over one real strategy, a mocked
     * {@link EngineDispatch}/{@link EngineReadiness} and a frozen clock — same shape as {@code
     * SchedulerEngineSwitchTest}'s fixture, reused rather than re-invented (the brief's
     * "reuse R1-R8" instruction applies to the fixture shape as much as to the assertions).
     */
    private static final class Fixture {
        final FunctionGeneration echo = new FunctionGeneration("echo", 1);
        final EngineDispatch dispatch = mock(EngineDispatch.class);
        final EngineReadiness readiness = mock(EngineReadiness.class);
        @SuppressWarnings("unchecked")
        final java.util.function.Predicate<FunctionGeneration> generationActive =
                mock(java.util.function.Predicate.class);
        final DispatchOwnership lease = mock(DispatchOwnership.class);
        final PendingWorkStore store;
        final SchedulerEngine engine;
        final List<String> submitted = new ArrayList<>();
        final List<String> expired = new ArrayList<>();
        final List<String> removed = new ArrayList<>();

        Fixture(SchedulingStrategy strategy) {
            this(strategy, new PendingWorkStore(1024));
        }

        Fixture(SchedulingStrategy strategy, PendingWorkStore store) {
            this.store = store;
            when(readiness.runnable(any())).thenReturn(true);
            when(generationActive.test(any())).thenReturn(true);
            when(dispatch.tryAcquire(any())).thenReturn(lease);
            doAnswer(inv -> submitted.add(((InvocationTask) inv.getArgument(0)).executionId()))
                    .when(dispatch).submit(any());
            doAnswer(inv -> expired.add(((InvocationTask) inv.getArgument(0)).executionId()))
                    .when(dispatch).expired(any());
            doAnswer(inv -> removed.add(((InvocationTask) inv.getArgument(0)).executionId()))
                    .when(dispatch).removed(any());
            this.engine = new SchedulerEngine(store, new StrategyRegistry(List.of(strategy)),
                    strategy.id(), dispatch, readiness, generationActive,
                    Clock.fixed(NOW, ZoneOffset.UTC), () -> 0L);
        }

        SchedulingTicket admit(String id, InvocationKind kind, FunctionGeneration generation, long seq) {
            return admit(id, kind, generation, seq, NOW, null);
        }

        SchedulingTicket admit(String id, InvocationKind kind, FunctionGeneration generation, long seq,
                               Instant notBefore, Instant queueDeadline) {
            SchedulingTicket ticket = new SchedulingTicket(new TicketId(id, 1), generation, seq,
                    NOW, notBefore, queueDeadline);
            InvocationTask task = new InvocationTask(id, generation.functionName(), null, null,
                    null, null, NOW, 1, kind);
            assertThat(engine.enqueue(new PendingEntry(ticket, task))).isTrue();
            return ticket;
        }

        SchedulingTicket admitFor(FunctionGeneration generation, String id, InvocationKind kind, long seq) {
            return admit(id, kind, generation, seq);
        }

        boolean tryAdmit(String id, InvocationKind kind, FunctionGeneration generation, long seq) {
            SchedulingTicket ticket = new SchedulingTicket(new TicketId(id, 1), generation, seq,
                    NOW, NOW, null);
            InvocationTask task = new InvocationTask(id, generation.functionName(), null, null,
                    null, null, NOW, 1, kind);
            return engine.enqueue(new PendingEntry(ticket, task));
        }
    }
}
