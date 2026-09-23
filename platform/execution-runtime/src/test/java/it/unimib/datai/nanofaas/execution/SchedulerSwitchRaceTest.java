package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.Outcome;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import it.unimib.datai.nanofaas.modules.asyncqueue.PerFunctionSchedulingStrategy;
import it.unimib.datai.nanofaas.modules.syncqueue.SharedQueueSchedulingStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The eight barrier-forced interleavings across the manual scheduler switch (issue #208, Task
 * 12b), and the cross-function turn-perturbation measurement the plan carried forward from Task
 * 2 on condition that this task produce a number for it.
 *
 * <p><strong>The centerpiece is {@link #claimThenSwitchThenLeaseAcquired_leaseIsReleasedAndTheTicketReachesTheNewIndexExactlyOnce}.</strong>
 * Task 10's equivalent test was structurally inert: its engine fixture had no {@link
 * FunctionCapacityRegistry} at all, so the lease/capacity assertions it made were against a
 * registry only {@code AttemptCoordinator} could see — no defect inside {@code switchTo} could
 * possibly flip them. Every test in this file that touches a lease shares ONE real {@link
 * FunctionCapacityRegistry} between the engine's {@link EngineDispatch} and whatever else needs
 * it, specifically to close that gap.
 *
 * <p>Every interleaving is forced with {@link CountDownLatch}s on real, separate threads — never
 * {@code sleep} — and the per-test {@code @Timeout} is a deadlock guard only, not a proof of
 * ordering: the ordering is forced structurally (a thread cannot proceed past a latch until the
 * other side reaches its own countdown), and every assertion is on an externally observable
 * outcome (dispatched/expired/removed multisets, held capacity, {@code Outcome} state) rather
 * than an internal flag.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class SchedulerSwitchRaceTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    // ------------------------------------------------------------------
    // 1. claim -> remove -> commit
    // ------------------------------------------------------------------

    @Test
    void claimThenRemoveThenCommit_ticketIsNotDispatchedAndItsCapacityIsNotLeaked() throws Exception {
        FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        capacity.register("echo", 4);
        AtomicReference<SchedulerEngine> engineRef = new AtomicReference<>();
        CapacityBackedDispatch dispatch = new CapacityBackedDispatch(capacity, () -> engineRef.get().signal());
        CountDownLatch leaseRequested = new CountDownLatch(1);
        CountDownLatch removeDone = new CountDownLatch(1);
        dispatch.beforeAcquire = () -> {
            leaseRequested.countDown();
            await(removeDone);
        };
        PendingWorkStore store = new PendingWorkStore(64);
        SchedulerEngine engine = newEngine(store, new PerFunctionSchedulingStrategy(), dispatch);
        engineRef.set(engine);
        TicketId ticketId = admit(engine, capacity, "e1", "echo", 1, NOW, null);

        Thread carrier = new Thread(engine::tick);
        carrier.start();
        await(leaseRequested);
        engine.remove(ticketId);
        removeDone.countDown();
        carrier.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(carrier.isAlive()).isFalse();
        assertThat(dispatch.submitted).isEmpty();
        assertThat(dispatch.removed).containsExactly("e1");
        assertThat(store.pendingCount()).isZero();
        assertThat(store.claimedCount()).isZero();
        // The lease WAS acquired from the real registry (beforeAcquire ran before capacity.
        // tryAcquireLease), and carry()'s null-current branch must have released it: a leak here
        // would show up as inFlight staying at 1 forever, which the non-race "remove a pending
        // ticket" test can never observe because it never lets a lease get acquired at all.
        assertThat(capacity.inFlight("echo")).isZero();
    }

    // ------------------------------------------------------------------
    // 2. claim -> switch -> lease acquired (the centerpiece)
    // ------------------------------------------------------------------

    /**
     * Is this genuinely falsifiable, unlike Task 10's version? Concretely: {@link
     * SchedulerEngine#carry} acquires the lease OUTSIDE the gate, then re-takes the gate and
     * checks {@code claim.epoch() != active.epoch()}; on a mismatch it aborts the claim, re-adds
     * the ticket to whichever index is active NOW, and releases the just-acquired lease. A
     * regression that dropped the {@code lease.release()} call from specifically that branch (as
     * opposed to the sibling "current == null" branch, which shares the same release line today
     * but could plausibly be split by a future edit) would leak one capacity slot from the real
     * {@link FunctionCapacityRegistry} every time a switch lands in this window — permanently,
     * for that generation. This test's own {@code capacity} instance is the SAME one {@code
     * dispatch.tryAcquire} calls into, so that leak is directly observable as {@code
     * capacity.inFlight(fn)} never returning to zero. A mock-lease fixture (Task 10's shape)
     * cannot detect this defect at all: {@code verify(lease).release()} stays green whether or
     * not the real registry's slot bookkeeping was ever touched, because the mock and the
     * registry are two different objects. That is the concrete failure mode this test closes.
     */
    @Test
    void claimThenSwitchThenLeaseAcquired_leaseIsReleasedAndTheTicketReachesTheNewIndexExactlyOnce()
            throws Exception {
        FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        capacity.register("echo", 4);
        AtomicReference<SchedulerEngine> engineRef = new AtomicReference<>();
        CapacityBackedDispatch dispatch = new CapacityBackedDispatch(capacity, () -> engineRef.get().signal());
        CountDownLatch claimTaken = new CountDownLatch(1);
        CountDownLatch switchCommitted = new CountDownLatch(1);
        dispatch.beforeAcquire = () -> {
            // The provisional claim (selectAndClaim) already happened, under the gate, before
            // carry() ever calls tryAcquire: signalling here means "claim taken", and the real
            // capacity.tryAcquireLease call below only happens once the switch has committed.
            claimTaken.countDown();
            await(switchCommitted);
        };
        PendingWorkStore store = new PendingWorkStore(64);
        SchedulingStrategy perFunction = new PerFunctionSchedulingStrategy();
        SchedulingStrategy sharedQueue = new SharedQueueSchedulingStrategy();
        SchedulerEngine engine = new SchedulerEngine(store,
                new StrategyRegistry(List.of(perFunction, sharedQueue)), perFunction.id(),
                dispatch, alwaysRunnable(), generation -> true, CLOCK, () -> 0L);
        engineRef.set(engine);
        admit(engine, capacity, "e1", "echo", 1, NOW, null);

        Thread carrier = new Thread(engine::tick);
        carrier.start();
        await(claimTaken);
        engine.switchTo(sharedQueue.id());
        switchCommitted.countDown();
        carrier.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(carrier.isAlive()).isFalse();
        // Not dispatched by the carry that raced the switch: the epoch mismatch aborted it.
        assertThat(dispatch.submitted).isEmpty();
        // The real slot was acquired (beforeAcquire ran, then the real tryAcquireLease call
        // happened) and then released by the epoch-mismatch branch: zero, not leaked.
        assertThat(capacity.inFlight("echo")).isZero();
        // The ticket must not be lost: it is back in the pending store, now under the NEW
        // strategy, and one more tick dispatches it — exactly once, never duplicated.
        assertThat(store.pendingCount()).isEqualTo(1);
        assertThat(engine.snapshot().strategy()).isEqualTo(sharedQueue.id());
        engine.tick();
        assertThat(dispatch.submitted).containsExactly("e1");
        assertThat(store.pendingCount()).isZero();
        assertThat(capacity.inFlight("echo")).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 3. switch build -> enqueue
    // ------------------------------------------------------------------

    @Test
    void switchBuildThenEnqueue_lateWorkIsNeverLostOrDuplicatedAcrossTheSwitch() throws Exception {
        FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        capacity.register("echo", 4);
        AtomicReference<SchedulerEngine> engineRef = new AtomicReference<>();
        CapacityBackedDispatch dispatch = new CapacityBackedDispatch(capacity, () -> engineRef.get().signal());
        PendingWorkStore store = new PendingWorkStore(64);
        CountDownLatch buildStarted = new CountDownLatch(1);
        CountDownLatch racerReachedTheGate = new CountDownLatch(1);
        SchedulingStrategy sharedQueue = pausingStrategy(new SharedQueueSchedulingStrategy(),
                buildStarted, racerReachedTheGate);
        SchedulerEngine engine = new SchedulerEngine(store,
                new StrategyRegistry(List.of(new PerFunctionSchedulingStrategy(), sharedQueue)),
                "per-function", dispatch, alwaysRunnable(), generation -> true, CLOCK, () -> 0L);
        engineRef.set(engine);
        admit(engine, capacity, "e1", "echo", 1, NOW, null);
        InvocationTask lateTask = new InvocationTask("late", "echo", null, null, null, null, NOW, 1,
                InvocationKind.ASYNC);
        SchedulingTicket lateTicket = new SchedulingTicket(new TicketId("late", 1),
                new FunctionGeneration("echo", 1), 1, NOW, NOW, null);

        Thread switcher = new Thread(() -> engine.switchTo(sharedQueue.id()));
        switcher.start();
        await(buildStarted);
        Thread enqueuer = new Thread(() -> {
            // Counted down from INSIDE the racer, immediately before it calls the gate: the
            // rebuild below is released by this thread's own arrival, so "the enqueue raced the
            // rebuild" is established rather than hoped for. A latch counted down by the test
            // thread right after start() says nothing about where the racer actually got to.
            racerReachedTheGate.countDown();
            engine.enqueue(new PendingEntry(lateTicket, lateTask));
        });
        enqueuer.start();
        // The gate is held for the whole rebuild, and the rebuild resumes only now — with the
        // enqueuer already at the gate — so this admission cannot possibly land mid-rebuild.
        switcher.join(TimeUnit.SECONDS.toMillis(5));
        enqueuer.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(switcher.isAlive()).isFalse();
        assertThat(enqueuer.isAlive()).isFalse();
        assertThat(store.pendingCount()).isEqualTo(2);
        engine.tick();
        engine.tick();
        assertThat(dispatch.submitted).containsExactlyInAnyOrder("e1", "late");
        assertThat(store.pendingCount()).isZero();
    }

    // ------------------------------------------------------------------
    // 4. switch build -> deadline
    // ------------------------------------------------------------------

    @Test
    void switchBuildThenDeadline_expiryIsNeitherLostNorDoubleReportedAcrossTheSwitch() throws Exception {
        FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        capacity.register("echo", 4);
        AtomicReference<SchedulerEngine> engineRef = new AtomicReference<>();
        CapacityBackedDispatch dispatch = new CapacityBackedDispatch(capacity, () -> engineRef.get().signal());
        PendingWorkStore store = new PendingWorkStore(64);
        CountDownLatch buildStarted = new CountDownLatch(1);
        CountDownLatch racerReachedTheGate = new CountDownLatch(1);
        SchedulingStrategy sharedQueue = pausingStrategy(new SharedQueueSchedulingStrategy(),
                buildStarted, racerReachedTheGate);
        SchedulerEngine engine = new SchedulerEngine(store,
                new StrategyRegistry(List.of(new PerFunctionSchedulingStrategy(), sharedQueue)),
                "per-function", dispatch, alwaysRunnable(), generation -> true, CLOCK, () -> 0L);
        engineRef.set(engine);
        // Already past its queue deadline: due for expiry the moment any pass runs.
        admit(engine, capacity, "due", "echo", 1, NOW, NOW.minusSeconds(1));
        admit(engine, capacity, "fresh", "echo", 2, NOW, null);

        Thread switcher = new Thread(() -> engine.switchTo(sharedQueue.id()));
        switcher.start();
        await(buildStarted);
        Thread ticker = new Thread(() -> {
            // Same shape as the enqueue race above: the ticker itself releases the rebuild, from
            // immediately before it calls the gate, so it is at the gate when the rebuild resumes.
            racerReachedTheGate.countDown();
            engine.tick();
        });
        ticker.start();
        // The gate serializes them: the reap cannot interleave with the rebuild, only precede or
        // follow it — and it follows it here, having reached the gate while the rebuild was paused.
        switcher.join(TimeUnit.SECONDS.toMillis(5));
        ticker.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(switcher.isAlive()).isFalse();
        assertThat(ticker.isAlive()).isFalse();
        // With the racer's own arrival releasing the rebuild, the order this test exercises is
        // fixed: the reap observes a completed switch, not a mid-rebuild index. The due ticket is
        // still expired exactly once — never lost (silently absent from every list) and never
        // double-reported. A single pass reaps due deadlines AND carries one selection to a
        // decision, so the same tick() that reaped "due" also dispatched "fresh": both are already
        // settled here, not after another tick.
        assertThat(dispatch.expired).containsExactly("due");
        assertThat(dispatch.submitted).containsExactly("fresh");
        assertThat(store.pendingCount()).isZero();
    }

    // ------------------------------------------------------------------
    // 5. switch commit -> completion vecchia -> retry
    // ------------------------------------------------------------------

    /**
     * Uses the engine's own {@link SchedulerEngine#setSwitchObserver} extension point as the
     * "commit" synchronization barrier: it fires exactly once, after the commit, outside the
     * gate — the documented seam for "something happens right after a switch lands" — rather
     * than a hostile hook invented for this test alone.
     */
    @Test
    void switchCommitThenOldCompletionThenRetry_theRetryReachesTheNewIndexExactlyOnce() throws Exception {
        FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        capacity.register("echo", 4);
        AtomicReference<SchedulerEngine> engineRef = new AtomicReference<>();
        CapacityBackedDispatch dispatch = new CapacityBackedDispatch(capacity, () -> engineRef.get().signal());
        PendingWorkStore store = new PendingWorkStore(64);
        SchedulingStrategy perFunction = new PerFunctionSchedulingStrategy();
        SchedulingStrategy sharedQueue = new SharedQueueSchedulingStrategy();
        SchedulerEngine engine = new SchedulerEngine(store,
                new StrategyRegistry(List.of(perFunction, sharedQueue)), perFunction.id(),
                dispatch, alwaysRunnable(), generation -> true, CLOCK, () -> 0L);
        engineRef.set(engine);
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch retryPublished = new CountDownLatch(1);
        engine.setSwitchObserver((strategy, outcome, durationNanos) -> {
            if (outcome == SchedulerEngine.SwitchOutcome.COMMITTED) {
                committed.countDown();
                await(retryPublished);
            }
        });
        // e1's original attempt is long gone (dispatched and completed elsewhere); what arrives
        // here is its retry, attempt 2, published by a completion that decided attempt 1 failed.
        InvocationTask retryTask = new InvocationTask("e1", "echo", null, null, null, null, NOW, 2,
                InvocationKind.ASYNC);
        SchedulingTicket retryTicket = new SchedulingTicket(new TicketId("e1", 2),
                new FunctionGeneration("echo", 1), 0, NOW, NOW, null);

        Thread switcher = new Thread(() -> engine.switchTo(sharedQueue.id()));
        switcher.start();
        await(committed);
        // The switch has committed (the observer already saw COMMITTED); the "old completion"
        // now publishes its retry against whatever is active — the new strategy.
        boolean admitted = engine.enqueue(new PendingEntry(retryTicket, retryTask));
        retryPublished.countDown();
        switcher.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(switcher.isAlive()).isFalse();
        assertThat(admitted).isTrue();
        assertThat(engine.snapshot().strategy()).isEqualTo(sharedQueue.id());
        assertThat(store.pendingCount()).isEqualTo(1);
        engine.tick();
        assertThat(dispatch.submitted).containsExactly("e1");
        assertThat(store.pendingCount()).isZero();
    }

    // ------------------------------------------------------------------
    // 6. reduce capacity -> commit
    // ------------------------------------------------------------------

    @Test
    void reduceCapacityThenCommit_anAlreadyAcquiredLeaseIsHonoredAndTheReductionOnlyBlocksFutureWork()
            throws Exception {
        FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        capacity.register("echo", 1);
        AtomicReference<SchedulerEngine> engineRef = new AtomicReference<>();
        CapacityBackedDispatch dispatch = new CapacityBackedDispatch(capacity, () -> engineRef.get().signal());
        CountDownLatch leaseAcquired = new CountDownLatch(1);
        CountDownLatch capacityReduced = new CountDownLatch(1);
        dispatch.afterAcquire = lease -> {
            if (lease != null) {
                leaseAcquired.countDown();
                await(capacityReduced);
            }
        };
        PendingWorkStore store = new PendingWorkStore(64);
        SchedulerEngine engine = newEngine(store, new PerFunctionSchedulingStrategy(), dispatch);
        engineRef.set(engine);
        admit(engine, capacity, "e1", "echo", 1, NOW, null);

        Thread carrier = new Thread(engine::tick);
        carrier.start();
        await(leaseAcquired);
        capacity.setEffectiveConcurrency("echo", 0);
        capacityReduced.countDown();
        carrier.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(carrier.isAlive()).isFalse();
        // The lease was already committed to before the reduction landed: it must still be
        // honored, not retroactively revoked.
        assertThat(dispatch.submitted).containsExactly("e1");
        assertThat(capacity.inFlight("echo")).isEqualTo(1);

        // A second, brand-new ticket now finds no room: the reduction blocks NEW work.
        admit(engine, capacity, "e2", "echo", 2, NOW, null);
        engine.tick();
        assertThat(dispatch.submitted).containsExactly("e1");
        assertThat(store.pendingCount()).isEqualTo(1);

        // e1's attempt finishes and releases its own slot, exactly as a real dispatch eventually
        // would — this test's own dispatch stub never runs a lifecycle, so it is simulated here.
        dispatch.heldLeases.get("e1").release();
        assertThat(capacity.inFlight("echo")).isZero();

        // Raising it back frees the second ticket, and the first attempt's slot is properly
        // released by then (no leak from the raced reduction).
        capacity.setEffectiveConcurrency("echo", 1);
        engine.signal();
        engine.tick();
        assertThat(dispatch.submitted).containsExactly("e1", "e2");
    }

    // ------------------------------------------------------------------
    // 7. snapshot runtime-config fallito -> nessun cambio
    // ------------------------------------------------------------------

    /**
     * The runtime-config admin API's own transactional commit is a Spring module this test
     * cannot reach without pulling Spring back onto execution-runtime's classpath (see
     * build.gradle's {@code transitive = false} comment). What it DOES own, and what
     * runtime-config's "no visible change on a failed snapshot" contract is actually built on top
     * of, is {@link SchedulerEngine#switchTo}'s own refusal contract: a failing preparation must
     * never let the target strategy become observably active, even for an instant, to a
     * concurrent reader. That is what this test pins, at the layer this module owns.
     */
    @Test
    void aFailedPreparationNeverLetsTheTargetStrategyBecomeObservablyActive() throws Exception {
        FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        capacity.register("echo", 4);
        AtomicReference<SchedulerEngine> engineRef = new AtomicReference<>();
        CapacityBackedDispatch dispatch = new CapacityBackedDispatch(capacity, () -> engineRef.get().signal());
        PendingWorkStore store = new PendingWorkStore(64);
        CountDownLatch aboutToFail = new CountDownLatch(1);
        CountDownLatch readDone = new CountDownLatch(1);
        SchedulingStrategy failing = new SchedulingStrategy() {
            @Override
            public String id() {
                return "shared-queue";
            }

            @Override
            public SchedulingIndex newIndex() {
                aboutToFail.countDown();
                await(readDone);
                throw new IllegalStateException("injected preparation failure");
            }
        };
        SchedulerEngine engine = new SchedulerEngine(store,
                new StrategyRegistry(List.of(new PerFunctionSchedulingStrategy(), failing)),
                "per-function", dispatch, alwaysRunnable(), generation -> true, CLOCK, () -> 0L);
        engineRef.set(engine);
        admit(engine, capacity, "e1", "echo", 1, NOW, null);
        List<String> observedDuringFailure = new CopyOnWriteArrayList<>();

        Thread switcher = new Thread(() -> {
            try {
                engine.switchTo(failing.id());
            } catch (SchedulerSwitchException expected) {
                // Expected: the injected failure refuses the switch.
            }
        });
        switcher.start();
        await(aboutToFail);
        observedDuringFailure.add(engine.snapshot().strategy());
        readDone.countDown();
        switcher.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(switcher.isAlive()).isFalse();
        assertThat(observedDuringFailure).containsExactly("per-function");
        assertThat(engine.snapshot().strategy()).isEqualTo("per-function");
        // No visible change means functionally alive too, not merely reporting the old id.
        engine.tick();
        assertThat(dispatch.submitted).containsExactly("e1");
    }

    // ------------------------------------------------------------------
    // 8. client disconnect -> commit -> GET
    // ------------------------------------------------------------------

    /**
     * Row 8 of the plan's switch-interleaving list, and the one row that exercises no switch at
     * all: no {@link SchedulerEngine}, no {@code switchTo}. It pins the attempt-record contract
     * the switch inherits — a client that stops waiting does not change what the server records —
     * over {@link ExecutionStore}/{@link ExecutionRecord} alone, and it would pass unchanged with
     * the whole switching feature reverted. Read it as a pinned premise of the rows around it,
     * not as evidence about the switch; the {@code isCancelled} check inside the committer is what
     * keeps even that reading honest, since without it the test cannot tell whether the client's
     * abandonment ever took effect.
     */
    @Test
    void clientDisconnectThenCommitThenGet_getReflectsTheRealOutcomeRegardlessOfTheDisconnect()
            throws Exception {
        ExecutionStore store = new ExecutionStore();
        InvocationTask task = new InvocationTask("e1", "echo", null, null, null, null, NOW, 1,
                InvocationKind.ASYNC);
        ExecutionRecord record = new ExecutionRecord("e1", task);
        store.put(record);
        record.markRunning();

        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch disconnected = new CountDownLatch(1);
        Thread disconnecter = new Thread(() -> {
            // The client gives up waiting: it stops watching the shared future. That must not
            // affect the server-side record of what actually happened.
            record.completion().cancel(false);
            order.add("disconnect");
            disconnected.countDown();
        });
        // The barrier pins THIS test's own serialization — the disconnect thread finishes before
        // the committer thread is started — so the interleaving the brief names is the one that
        // runs rather than whichever order the two threads happened to reach the record in. No
        // sleep is involved. It is a harness fact, not a production one: what this test claims
        // about the product is asserted below, inside the committer and on the archived outcome.
        disconnecter.start();
        await(disconnected);

        AtomicReference<Throwable> committerFailure = new AtomicReference<>();
        Thread committer = new Thread(() -> {
            try {
                synchronized (record) {
                    // The premise, checked rather than assumed: the commit really does land on an
                    // execution whose shared future the client has already abandoned. Without this
                    // the test would still pass if cancel(false) had never taken effect.
                    assertThat(record.completion().isCancelled())
                            .as("the commit must land after the client's own wait was cancelled")
                            .isTrue();
                    record.markSuccess("ok", 200, java.util.Map.of(), null);
                }
                store.settle(record);
                order.add("commit");
            } catch (Throwable failure) {
                committerFailure.compareAndSet(null, failure);
            }
        });
        committer.start();
        committer.join(TimeUnit.SECONDS.toMillis(5));
        disconnecter.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(committerFailure.get()).isNull();
        assertThat(committer.isAlive()).isFalse();
        assertThat(disconnecter.isAlive()).isFalse();
        // The test's own barrier, made explicit: with the committer started only after the
        // disconnect, this list can hold nothing else — so it documents the harness ordering and
        // is not evidence of a race. The checked premise inside the committer is.
        assertThat(order).containsExactly("disconnect", "commit");

        // GET, after both: the live record is gone (terminal + settled), but the archived
        // outcome — exactly what GET /v1/executions/{id} serves — reflects the true commit that
        // landed after the client's own wait was already cancelled.
        assertThat(store.getOrNull("e1")).isNull();
        Outcome outcome = store.outcomeOf("e1");
        assertThat(outcome).isNotNull();
        assertThat(outcome.state()).isEqualTo(ExecutionState.SUCCESS);
    }

    // ------------------------------------------------------------------
    // Cross-function turn-perturbation measurement (Task 2 carry-forward)
    // ------------------------------------------------------------------

    /**
     * {@code PerFunctionSchedulingStrategy.remove} resets {@code turnFunction}/{@code turnCount}
     * whenever the removed ticket belongs to a different function than the one currently
     * mid-turn — including when that removal is an out-of-band one (an expiry or an explicit
     * {@link SchedulerEngine#remove}), not a normal dispatch. This measures the concrete effect:
     * with function A mid-turn (one dispatch in, batch cap 2) and an out-of-band removal landing
     * on a DIFFERENT function B in that window, A's turn counter resets and A dispatches an extra
     * consecutive ticket beyond its documented cap before finally rotating away, using the exact
     * per-function turn management the real strategy runs — no mock/hostile index involved.
     */
    @Test
    void crossFunctionTurnPerturbation_outOfBandRemovalOfAnotherFunctionExtendsThisFunctionsTurnByOne() {
        FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        capacity.register("fn-a", 10);
        capacity.register("fn-b", 10);
        AtomicReference<SchedulerEngine> engineRef = new AtomicReference<>();
        CapacityBackedDispatch dispatch = new CapacityBackedDispatch(capacity, () -> engineRef.get().signal());
        PendingWorkStore store = new PendingWorkStore(64);
        SchedulerEngine engine = newEngine(store, new PerFunctionSchedulingStrategy(), dispatch);
        engineRef.set(engine);
        admit(engine, capacity, "a1", "fn-a", 1, NOW, null);
        admit(engine, capacity, "a2", "fn-a", 2, NOW, null);
        admit(engine, capacity, "a3", "fn-a", 3, NOW, null);
        TicketId b1 = admit(engine, capacity, "b1", "fn-b", 4, NOW, null);
        admit(engine, capacity, "b2", "fn-b", 5, NOW, null);

        // a1 dispatches: A is now mid-turn, one in (turnCount == 1, cap is 2).
        engine.tick();
        assertThat(dispatch.submitted).containsExactly("a1");
        // Out-of-band removal of a DIFFERENT function's ticket (b1), landing exactly in that
        // mid-turn window: this is the documented perturbation.
        engine.remove(b1);
        assertThat(dispatch.removed).containsExactly("b1");

        // a2, a3 then dispatch consecutively as well — THREE in a row for A, one more than the
        // documented DEFAULT_MAX_BATCH_PER_FUNCTION == 2 — because the interruption reset A's
        // counter back to zero instead of letting it reach the cap after a2.
        engine.tick();
        engine.tick();
        assertThat(dispatch.submitted).containsExactly("a1", "a2", "a3");

        // Self-healing: B gets its remaining ticket next, and nothing is starved or lost overall.
        engine.tick();
        assertThat(dispatch.submitted).containsExactly("a1", "a2", "a3", "b2");
        assertThat(store.pendingCount()).isZero();
    }

    /** Baseline for the measurement above: the SAME removal, but outside A's turn window, never
     * perturbs anything — A dispatches exactly its capped batch of 2 before rotating. */
    @Test
    void crossFunctionTurnPerturbation_baselineWithoutTheInterruptionRespectsTheBatchCap() {
        FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        capacity.register("fn-a", 10);
        capacity.register("fn-b", 10);
        AtomicReference<SchedulerEngine> engineRef = new AtomicReference<>();
        CapacityBackedDispatch dispatch = new CapacityBackedDispatch(capacity, () -> engineRef.get().signal());
        PendingWorkStore store = new PendingWorkStore(64);
        SchedulerEngine engine = newEngine(store, new PerFunctionSchedulingStrategy(), dispatch);
        engineRef.set(engine);
        admit(engine, capacity, "a1", "fn-a", 1, NOW, null);
        admit(engine, capacity, "a2", "fn-a", 2, NOW, null);
        admit(engine, capacity, "a3", "fn-a", 3, NOW, null);
        admit(engine, capacity, "b1", "fn-b", 4, NOW, null);
        admit(engine, capacity, "b2", "fn-b", 5, NOW, null);

        engine.tick();
        engine.tick();
        // No interruption here: A rotates away after exactly 2 consecutive dispatches.
        assertThat(dispatch.submitted).containsExactly("a1", "a2");
        engine.tick();
        engine.tick();
        assertThat(dispatch.submitted).containsExactly("a1", "a2", "b1", "b2");
    }

    // ------------------------------------------------------------------
    // Shared fixture
    // ------------------------------------------------------------------

    private static SchedulerEngine newEngine(PendingWorkStore store, SchedulingStrategy strategy,
                                             EngineDispatch dispatch) {
        return new SchedulerEngine(store, new StrategyRegistry(List.of(strategy)), strategy.id(),
                dispatch, alwaysRunnable(), generation -> true, CLOCK, () -> 0L);
    }

    private static EngineReadiness alwaysRunnable() {
        return generation -> true;
    }

    private static TicketId admit(SchedulerEngine engine, FunctionCapacityRegistry capacity, String id,
                                  String functionName, long sequence, Instant notBefore, Instant deadline) {
        TicketId ticketId = new TicketId(id, 1);
        // The registry mints generation ids from ONE counter shared across every function it
        // registers, not a per-function counter starting at 1: the second function registered on
        // a given registry gets generation id 2, not "functionName#1". Reading the real active
        // generation back from the registry (rather than assuming id 1) is what keeps the
        // ticket's generation matching what capacity.tryAcquireLease actually checks against.
        FunctionGeneration generation = capacity.activeGeneration(functionName);
        assertThat(generation).as("function %s must be registered with capacity before admit()", functionName)
                .isNotNull();
        SchedulingTicket ticket = new SchedulingTicket(ticketId, generation,
                sequence, NOW, notBefore, deadline);
        InvocationTask task = new InvocationTask(id, functionName, null, null, null, null, NOW, 1,
                InvocationKind.ASYNC);
        assertThat(engine.enqueue(new PendingEntry(ticket, task))).isTrue();
        return ticketId;
    }

    /**
     * Wraps a real strategy's real index, pausing on a latch pair the first time {@code add} is
     * called during a rebuild — i.e. exactly once per {@code switchTo}, inside the gate. Used to
     * force "something else raced the build" interleavings deterministically. The underlying
     * index and its selection/turn logic are entirely real; only the pause point is synthetic,
     * the same technique {@code SchedulerEngineSwitchTest} already uses for its own concurrent
     * enqueue test.
     */
    private static SchedulingStrategy pausingStrategy(SchedulingStrategy delegate,
                                                       CountDownLatch buildStarted, CountDownLatch releaseBuild) {
        return new SchedulingStrategy() {
            @Override
            public String id() {
                return delegate.id();
            }

            @Override
            public SchedulingIndex newIndex() {
                SchedulingIndex real = delegate.newIndex();
                return new SchedulingIndex() {
                    private boolean paused;

                    @Override
                    public void add(SchedulingTicket ticket) {
                        if (!paused) {
                            paused = true;
                            buildStarted.countDown();
                            await(releaseBuild);
                        }
                        real.add(ticket);
                    }

                    @Override
                    public void remove(TicketId id) {
                        real.remove(id);
                    }

                    @Override
                    public SchedulingTicket select(Instant now, Predicate<FunctionGeneration> runnable) {
                        return real.select(now, runnable);
                    }

                    @Override
                    public void defer(TicketId id) {
                        real.defer(id);
                    }

                    @Override
                    public int size() {
                        return real.size();
                    }

                    @Override
                    public void clear() {
                        real.clear();
                    }
                };
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for latch — deadlock guard, not an ordering proof");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /**
     * An {@link EngineDispatch} whose {@code tryAcquire} calls into a REAL {@link
     * FunctionCapacityRegistry} — the same instance a test can assert against directly — instead
     * of returning a bare mock lease. {@code beforeAcquire} runs (and may block) just before the
     * real acquisition; {@code afterAcquire} runs (and may block) just after it succeeds, with
     * the acquired lease already in hand but not yet returned to the engine.
     */
    private static final class CapacityBackedDispatch implements EngineDispatch {
        private final FunctionCapacityRegistry capacity;
        private final Runnable engineSignal;
        final List<String> submitted = new CopyOnWriteArrayList<>();
        final List<String> expired = new CopyOnWriteArrayList<>();
        final List<String> removed = new CopyOnWriteArrayList<>();
        /** Leases handed to a successful {@code submit}, by execution id — a real attempt would
         * hold this until its own completion; a test simulating "the attempt finished" calls
         * {@code release()} on the value here, exactly as the real lifecycle eventually would. */
        final java.util.Map<String, DispatchOwnership> heldLeases = new java.util.concurrent.ConcurrentHashMap<>();
        volatile Runnable beforeAcquire = () -> { };
        volatile java.util.function.Consumer<DispatchOwnership> afterAcquire = lease -> { };
        private volatile DispatchOwnership lastAcquired;

        CapacityBackedDispatch(FunctionCapacityRegistry capacity, Runnable engineSignal) {
            this.capacity = capacity;
            this.engineSignal = engineSignal;
        }

        @Override
        public DispatchOwnership tryAcquire(SchedulingTicket ticket) {
            beforeAcquire.run();
            DispatchOwnership lease = capacity.tryAcquireLease(ticket.generation(),
                    releasedNanos -> engineSignal.run());
            afterAcquire.accept(lease);
            lastAcquired = lease;
            return lease;
        }

        @Override
        public void submit(InvocationTask task) {
            submitted.add(task.executionId());
            DispatchOwnership lease = lastAcquired;
            if (lease != null) {
                heldLeases.put(task.executionId(), lease);
            }
        }

        @Override
        public void expired(InvocationTask task) {
            expired.add(task.executionId());
        }

        @Override
        public void removed(InvocationTask task) {
            removed.add(task.executionId());
        }

        @Override
        public void rejected(InvocationTask task, Throwable failure) {
            // Not exercised by this file's interleavings.
        }
    }
}
