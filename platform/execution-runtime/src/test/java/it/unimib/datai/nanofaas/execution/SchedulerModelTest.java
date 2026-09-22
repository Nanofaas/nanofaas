package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.Outcome;
import it.unimib.datai.nanofaas.controlplane.execution.TimeSource;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import it.unimib.datai.nanofaas.controlplane.service.RetryScheduler;
import it.unimib.datai.nanofaas.modules.asyncqueue.PerFunctionSchedulingStrategy;
import it.unimib.datai.nanofaas.modules.syncqueue.SharedQueueSchedulingStrategy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Model-based test for the scheduling engine under a random mix of admission, removal, clock
 * advance, capacity change and manual strategy switch (issue #208, Task 12, brief step 3).
 *
 * <p>The reference model tracks the terminal sets, which function each live ticket id belongs to,
 * and the brief's <strong>attempts map</strong>: per execution id, the attempt the engine is
 * supposed to be on. {@code complete} is a <strong>real completion driven through the
 * engine</strong>, not a remove-no-op on ids that happen not to be live. {@link
 * EngineDispatch#submit} hands the committed attempt to a real {@link AttemptCoordinator};
 * {@code complete} feeds that attempt's logical outcome and its physical drain back into it; and
 * the coordinator's own retry decision ({@code !success && attempt <= maxRetries}, the next task
 * built by {@code prepareRetry}) republishes through a real {@link RetryScheduler} into this same
 * {@link SchedulerEngine}, as one more ticket whose id is {@code (executionId, attempt + 1)} and
 * whose generation comes from the real registry — exactly the shape {@code
 * EngineInvocationEnqueuer} gives a retry in production, and the shape {@code
 * SchedulerSwitchRaceTest}'s row 5 drives by hand. Attempt accounting, the retry budget and the
 * retry re-queue are therefore inside the fuzzer's reach; a defect in any of them moves an
 * assertion here.
 *
 * <p>After every single-threaded operation the real engine must agree with the model on
 * <strong>work conservation</strong> (every admitted id ends up in exactly one place: still live,
 * or in exactly one of removed/expired/completed), <strong>idempotence</strong> (removing an id
 * twice, or an id that was never admitted, changes nothing) and structural
 * <strong>invariants</strong>: {@code pendingCount} and {@code reservedCount} match the model's
 * queued tickets per function, the real {@link FunctionCapacityRegistry}'s {@code inFlight}
 * matches the model's in-flight attempts per function (so a leaked or double-released dispatch
 * lease shows up), {@code claimed}/{@code submitting} are always empty between operations (no
 * worker thread, no deferred submit), each live id's execution record still carries the attempt
 * the model's attempts map says it is on, and no id is both live and terminal. It deliberately
 * does not assert dispatch <em>order</em> — that differs legitimately between the two real
 * strategies exercised here in the same run via {@code switch}, and ordering is covered by the
 * strategies' own FIFO/fairness tests, not by this one.
 */
class SchedulerModelTest {

    private static final long SEED = 208L;
    private static final int OPERATIONS = 10_000;
    /** The retry budget every function here is configured with: attempts 1..MAX_RETRIES + 1. */
    private static final int MAX_RETRIES = 2;
    private static final String[] FUNCTIONS = {"fn-0", "fn-1", "fn-2", "fn-3", "fn-4"};

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-16T10:00:00Z"));
    private final Map<String, Boolean> runnableByFunction = new HashMap<>();
    /** Every dispatched attempt as "executionId#attempt" — the no-double-dispatch multiset. */
    private final Set<String> submittedAttempts = new HashSet<>();
    private final Set<String> removedIds = new HashSet<>();
    private final Set<String> expiredIds = new HashSet<>();
    /** Model: live (admitted, not yet terminal) ticket ids, and the function each belongs to. */
    private final Map<String, String> liveByFunction = new HashMap<>();
    private final Deque<String> liveIdsOrder = new ArrayDeque<>();
    /**
     * Model: the brief's attempts map — per execution id, the attempt the engine is on for it.
     * Advanced by this model when it scores a completion as a retry, so the retry's own dispatch
     * is checked against the number the engine's task carries, never merely recorded from it.
     */
    private final Map<String, Integer> attemptById = new HashMap<>();
    /** Model: live ids whose committed attempt is in flight — dispatched, not yet completed. */
    private final Set<String> inFlightIds = new HashSet<>();
    /** Model: ids that reached a terminal execution state through a real completion. */
    private final Map<String, ExecutionState> completedStateById = new HashMap<>();
    private final Set<String> everAdmitted = new HashSet<>();
    private final Map<String, TicketId> ticketIdById = new HashMap<>();
    /** The handle of each in-flight attempt, so {@code complete} can drive its outcome. */
    private final Map<String, AttemptHandle> inFlightHandles = new HashMap<>();
    private final Map<String, FunctionGeneration> generations = new HashMap<>();
    private final StringBuilder opLog = new StringBuilder();

    private final FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
    private final ExecutionStore executions = new ExecutionStore();
    private final PendingWorkStore store = new PendingWorkStore(100_000);
    private final AtomicReference<SchedulerEngine> engineRef = new AtomicReference<>();

    private SchedulerEngine engine;
    private long sequenceCursor;
    private int idCounter;
    private int retryCount;

    @Test
    void tenThousandRandomOperationsPreserveWorkConservationIdempotenceAndInvariants() {
        Random random = new Random(SEED);
        PerFunctionSchedulingStrategy perFunction = new PerFunctionSchedulingStrategy();
        SharedQueueSchedulingStrategy sharedQueue = new SharedQueueSchedulingStrategy();
        StrategyRegistry registry = new StrategyRegistry(List.of(perFunction, sharedQueue));

        for (String function : FUNCTIONS) {
            capacity.register(function, 64);
            generations.put(function, capacity.activeGeneration(function));
        }

        EngineReadiness readiness = mock(EngineReadiness.class);
        when(readiness.runnable(any())).thenAnswer(inv -> {
            FunctionGeneration generation = inv.getArgument(0);
            return runnableByFunction.getOrDefault(generation.functionName(), true);
        });

        AttemptTransport transport = task -> {
            AttemptHandle handle = new AttemptHandle(new CompletableFuture<>(),
                    new CompletableFuture<>(), new CompletableFuture<>());
            inFlightHandles.put(task.executionId(), handle);
            return handle;
        };
        // A real retry: the coordinator's next attempt becomes one more ticket in this same
        // engine, under id (executionId, attempt).
        RetryScheduler retry = task -> engineRef.get().enqueue(new PendingEntry(
                new SchedulingTicket(new TicketId(task.executionId(), task.attempt()),
                        generations.get(task.functionName()), sequenceCursor++, clock.instant(),
                        clock.instant(), null), task));
        AttemptCoordinator coordinator = new AttemptCoordinator(executions, capacity, retry,
                transport, TimeSource.system(), mock(AttemptObserver.class));

        EngineDispatch dispatch = mock(EngineDispatch.class);
        when(dispatch.isCurrent(any())).thenReturn(true);
        when(dispatch.tryAcquire(any())).thenAnswer(inv -> {
            SchedulingTicket ticket = inv.getArgument(0);
            return capacity.tryAcquireLease(ticket.generation(), ignored -> engineRef.get().signal());
        });
        doAnswer(inv -> {
            InvocationTask task = (InvocationTask) inv.getArgument(0);
            assertTrue(submittedAttempts.add(task.executionId() + "#" + task.attempt()),
                    "an attempt was dispatched twice: " + task.executionId() + "#" + task.attempt());
            Integer expected = attemptById.get(task.executionId());
            assertTrue(expected != null && expected == task.attempt(),
                    "attempt mismatch for " + task.executionId() + ": the model expected attempt "
                            + expected + " but the engine dispatched attempt " + task.attempt());
            inFlightIds.add(task.executionId());
            coordinator.dispatch(task);
            return null;
        }).when(dispatch).submit(any());
        doAnswer(inv -> {
            InvocationTask task = (InvocationTask) inv.getArgument(0);
            assertTrue(expiredIds.add(task.executionId()),
                    "a ticket expired twice: " + task.executionId());
            executions.expired(task);
            return null;
        }).when(dispatch).expired(any());
        doAnswer(inv -> {
            InvocationTask task = (InvocationTask) inv.getArgument(0);
            assertTrue(removedIds.add(task.executionId()),
                    "a ticket was removed twice: " + task.executionId());
            executions.removed(task);
            return null;
        }).when(dispatch).removed(any());

        engine = new SchedulerEngine(store, registry, "per-function", dispatch, readiness, clock,
                () -> 0L);
        engineRef.set(engine);

        try {
            for (int op = 0; op < OPERATIONS; op++) {
                applyRandomOperation(random);
                checkInvariants(op);
            }
            checkWorkConservation();
            // Non-vacuity, the same guard RuntimeArchitectureTest puts on its own import: a run
            // where no execution ever completed, or none ever retried, would satisfy every
            // invariant above by never exercising the half this test exists to cover.
            assertThat(completedStateById)
                    .as("the completion op must actually have concluded executions through the engine")
                    .isNotEmpty();
            assertThat(retryCount)
                    .as("a real completion must actually have driven retry re-queues")
                    .isGreaterThan(0);
            assertThat(completedStateById.values())
                    .as("the retry budget must actually have been exhausted at least once, so the "
                            + "ERROR terminal branch is exercised and not merely reachable")
                    .contains(ExecutionState.ERROR);
            assertThat(completedStateById.values())
                    .as("a successful completion must actually have been concluded through the "
                            + "engine, so the SUCCESS terminal branch — the dominant one — is "
                            + "exercised and not merely reachable")
                    .contains(ExecutionState.SUCCESS);
        } catch (AssertionError | RuntimeException failure) {
            throw new AssertionError("Model test failed with seed " + SEED
                    + " after operation log:\n" + opLog, failure);
        }
    }

    private void applyRandomOperation(Random random) {
        int choice = random.nextInt(7);
        switch (choice) {
            case 0 -> enqueue(random);
            case 1 -> removeQueued(random);
            case 2 -> tick();
            case 3 -> advanceClock(random);
            case 4 -> changeCapacity(random);
            case 5 -> switchStrategy(random);
            default -> complete(random);
        }
    }

    private void enqueue(Random random) {
        String function = FUNCTIONS[random.nextInt(FUNCTIONS.length)];
        String id = "op-" + idCounter++;
        boolean delayed = random.nextInt(10) == 0;
        boolean hasDeadline = random.nextInt(5) == 0;
        Instant now = clock.instant();
        Instant notBefore = delayed ? now.plusSeconds(1 + random.nextInt(120)) : now;
        Instant deadline = hasDeadline ? now.plusSeconds(random.nextInt(60) - 30) : null;
        TicketId ticketId = new TicketId(id, 1);
        SchedulingTicket ticket = new SchedulingTicket(ticketId, generations.get(function),
                sequenceCursor++, now, notBefore, deadline);
        InvocationTask task = new InvocationTask(id, function, specFor(function), null, null, null,
                now, 1, InvocationKind.ASYNC);
        boolean admitted = engine.enqueue(new PendingEntry(ticket, task));
        log("enqueue(" + id + ", fn=" + function + ", notBefore=+" + (notBefore.getEpochSecond()
                - now.getEpochSecond()) + "s, deadline=" + deadline + ") -> " + admitted);
        if (admitted) {
            executions.put(new ExecutionRecord(id, task));
            liveByFunction.put(id, function);
            liveIdsOrder.addLast(id);
            attemptById.put(id, 1);
            everAdmitted.add(id);
            ticketIdById.put(id, ticketId);
        }
    }

    /**
     * Withdraws a ticket that is still queued, and in the same operation exercises both no-op
     * halves of the removal contract: removing an id that is already terminal, and removing an id
     * that was never admitted at all. Neither may change anything — a second decrement of the
     * function's reservation count would show up against the model's own queued count at the next
     * operation, and a spurious {@code dispatch.removed} against the double-removal guard.
     *
     * <p>A ticket whose attempt is already in flight is deliberately out of reach: {@link
     * SchedulerEngine#remove} can only withdraw pending work — {@link PendingWorkStore#remove}
     * refuses a submitting ticket — so concluding an in-flight attempt is the {@code complete}
     * op's business here, exactly as the lifecycle's own cancellation path is in production.
     */
    private void removeQueued(Random random) {
        List<String> queued = liveIdsOrder.stream().filter(id -> !inFlightIds.contains(id)).toList();
        if (queued.isEmpty()) {
            log("remove(none queued) -> skipped");
        } else {
            String id = queued.get(random.nextInt(queued.size()));
            engine.remove(ticketIdById.get(id));
            engine.remove(ticketIdById.get(id));
            log("remove(" + id + ") twice");
            // The engine settles synchronously here (no worker thread, no in-flight submit), so
            // the ticket is either freshly removed (reported through dispatch.removed, already
            // asserted for double-firing above) or was already gone. Either way it leaves the
            // live model.
            liveByFunction.remove(id);
            liveIdsOrder.remove(id);
            attemptById.remove(id);
        }
        TicketId neverAdmitted = new TicketId("never-admitted-" + random.nextInt(1_000_000), 1);
        engine.remove(neverAdmitted);
        log("remove(" + neverAdmitted.executionId() + ") -> never admitted, no-op");
    }

    private void tick() {
        engine.tick();
        log("tick()");
        // Reconcile the model against whatever the tick just settled terminally — a reap of a due
        // queue deadline. An id this tick merely dispatched stays live: it is in flight now, not
        // terminal, and leaves the live set only when its own completion lands.
        liveIdsOrder.removeIf(id -> {
            boolean settled = removedIds.contains(id) || expiredIds.contains(id);
            if (settled) {
                liveByFunction.remove(id);
                attemptById.remove(id);
            }
            return settled;
        });
    }

    private void advanceClock(Random random) {
        long seconds = 1L + random.nextInt(90);
        clock.advance(seconds);
        log("advanceClock(+" + seconds + "s) -> " + clock.instant());
    }

    private void changeCapacity(Random random) {
        String function = FUNCTIONS[random.nextInt(FUNCTIONS.length)];
        boolean nowRunnable = random.nextBoolean();
        runnableByFunction.put(function, nowRunnable);
        log("changeCapacity(" + function + ", runnable=" + nowRunnable + ")");
    }

    private void switchStrategy(Random random) {
        String target = random.nextBoolean() ? "per-function" : "shared-queue";
        try {
            engine.switchTo(target);
            log("switch(" + target + ") -> ok");
        } catch (SchedulerSwitchException refused) {
            // A refusal must never lose or duplicate a ticket: the previous strategy stays
            // active and every live id is exactly where it was before the attempt.
            log("switch(" + target + ") -> refused: " + refused.reason());
        }
    }

    /**
     * The brief's "complete" operation: it concludes a real in-flight attempt through the
     * engine's own lifecycle. The attempt's logical outcome and its physical drain are fed back
     * into the {@link AttemptCoordinator} that owns it, which settles the record, releases the
     * dispatch lease through the real registry and — for a failure still inside the retry budget
     * — republishes the next attempt into this engine. The attempts map is advanced in the same
     * step, so the retry's dispatch is checked against the number this model predicted rather
     * than against whatever the engine happened to do.
     */
    private void complete(Random random) {
        if (inFlightIds.isEmpty()) {
            log("complete(nothing in flight) -> skipped");
            return;
        }
        List<String> inFlight = new ArrayList<>(inFlightIds);
        String id = inFlight.get(random.nextInt(inFlight.size()));
        int attempt = attemptById.get(id);
        boolean success = random.nextInt(3) != 0;
        AttemptHandle handle = inFlightHandles.remove(id);
        handle.outcome().complete(DispatchResult.warm(success
                ? InvocationResult.success("ok")
                : InvocationResult.error("ATTEMPT_FAILED", "injected attempt failure")));
        handle.drained().complete(null);
        inFlightIds.remove(id);
        if (!success && attempt <= MAX_RETRIES) {
            attemptById.put(id, attempt + 1);
            ticketIdById.put(id, new TicketId(id, attempt + 1));
            retryCount++;
            log("complete(" + id + ", attempt=" + attempt + ", success=false) -> retry attempt "
                    + (attempt + 1));
            // The coordinator republished attempt+1 into this engine synchronously above; that it
            // really landed is what the queued-count and reservedCount invariants check from here.
        } else {
            ExecutionState expected = success ? ExecutionState.SUCCESS : ExecutionState.ERROR;
            Outcome outcome = executions.outcomeOf(id);
            assertTrue(outcome != null && outcome.state() == expected,
                    "complete(" + id + ", attempt=" + attempt + ", success=" + success
                            + ") must archive the execution's real outcome as " + expected
                            + " but the store holds "
                            + (outcome == null ? "no outcome" : outcome.state()));
            completedStateById.put(id, expected);
            liveByFunction.remove(id);
            liveIdsOrder.remove(id);
            attemptById.remove(id);
            log("complete(" + id + ", attempt=" + attempt + ", success=" + success + ") -> terminal "
                    + expected);
        }
    }

    private void checkInvariants(int op) {
        assertThat(store.claimedCount())
                .as("op %d: claimed must always be empty between operations (no worker thread here)", op)
                .isZero();
        assertThat(store.submittingCount())
                .as("op %d: submitting must always be empty between operations", op)
                .isZero();
        long queued = (long) liveIdsOrder.size() - inFlightIds.size();
        assertThat((long) store.pendingCount())
                .as("op %d: pendingCount must match the model's live, not-yet-dispatched tickets", op)
                .isEqualTo(queued);
        Map<String, Long> queuedByFunction = new HashMap<>();
        Map<String, Long> inFlightByFunction = new HashMap<>();
        for (String id : liveIdsOrder) {
            (inFlightIds.contains(id) ? inFlightByFunction : queuedByFunction)
                    .merge(liveByFunction.get(id), 1L, Long::sum);
        }
        for (String function : FUNCTIONS) {
            assertThat((long) engine.reservedCount(function))
                    .as("op %d: reservedCount(%s) must match the model's queued count for it", op, function)
                    .isEqualTo(queuedByFunction.getOrDefault(function, 0L));
            assertThat((long) capacity.inFlight(function))
                    .as("op %d: the real registry's inFlight(%s) must match the model's in-flight "
                            + "attempts — a leaked or double-released dispatch lease shows up here",
                            op, function)
                    .isEqualTo(inFlightByFunction.getOrDefault(function, 0L));
        }
        // No id is ever in two terminal sets, nor both live and terminal (idempotence + work
        // conservation, checked incrementally rather than only at the end).
        for (String id : liveByFunction.keySet()) {
            assertThat(removedIds).as("op %d: %s live and removed", op, id).doesNotContain(id);
            assertThat(expiredIds).as("op %d: %s live and expired", op, id).doesNotContain(id);
            assertThat(completedStateById).as("op %d: %s live and completed", op, id)
                    .doesNotContainKey(id);
            ExecutionRecord record = executions.getOrNull(id);
            assertThat(record).as("op %d: %s is live but its execution record was settled", op, id)
                    .isNotNull();
            assertThat(record.task().attempt())
                    .as("op %d: %s is live on a different attempt than the model's attempts map "
                            + "says", op, id)
                    .isEqualTo(attemptById.get(id));
        }
    }

    private void checkWorkConservation() {
        long terminal = (long) removedIds.size() + expiredIds.size() + completedStateById.size();
        assertThat(terminal + liveIdsOrder.size())
                .as("every admitted ticket must end up live, removed, expired or completed — never "
                        + "more than one and never zero")
                .isEqualTo(everAdmitted.size());
        assertThat((long) store.pendingCount()).isEqualTo(liveIdsOrder.size() - inFlightIds.size());
    }

    /**
     * {@code timeoutMs} is the attempt deadline the coordinator arms on each attempt; ten minutes
     * is a deadlock guard, not a policy — nothing here is meant to time out.
     */
    private static FunctionSpec specFor(String function) {
        return new FunctionSpec(function, "test-image", null, null, null, 600_000, 4, 1_000,
                MAX_RETRIES, null, ExecutionMode.LOCAL, null, null, null);
    }

    private void log(String entry) {
        opLog.append(entry).append('\n');
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /** A {@link Clock} the test can advance deterministically, without sleeping. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
