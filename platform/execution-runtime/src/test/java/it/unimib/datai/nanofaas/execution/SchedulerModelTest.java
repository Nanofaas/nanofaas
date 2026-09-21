package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Model-based test for the scheduling engine under a random mix of admission, removal, clock
 * advance, capacity change and manual strategy switch (issue #208, Task 12, brief step 3).
 *
 * <p>The reference model here is deliberately narrow: it tracks which ticket ids are still
 * live (admitted, not yet terminal) and which function each belongs to, and checks that after
 * every single-threaded operation the real {@link SchedulerEngine} agrees with it on
 * <strong>work conservation</strong> (every admitted id ends up in exactly one place: still
 * pending, or in exactly one of submitted/removed/expired), <strong>idempotence</strong>
 * (removing an id twice, or an id that was never admitted, changes nothing) and structural
 * <strong>invariants</strong> ({@code reservedCount} matches live tickets per function,
 * {@code claimed}/{@code submitting} are always empty between operations because nothing here
 * ever runs the worker thread or defers a submit). It deliberately does not assert dispatch
 * <em>order</em> — that differs legitimately between the two real strategies exercised here in
 * the same run via {@code switch}, and ordering is covered by the strategies' own FIFO/fairness
 * tests, not by this one.
 */
class SchedulerModelTest {

    private static final long SEED = 208L;
    private static final int OPERATIONS = 10_000;
    private static final String[] FUNCTIONS = {"fn-0", "fn-1", "fn-2", "fn-3", "fn-4"};

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-16T10:00:00Z"));
    private final Map<String, Boolean> runnableByFunction = new HashMap<>();
    private final List<String> submitted = new ArrayList<>();
    private final Set<String> submittedIds = new HashSet<>();
    private final Set<String> removedIds = new HashSet<>();
    private final Set<String> expiredIds = new HashSet<>();
    /** Model: live (admitted, not yet terminal) ticket ids, and the function each belongs to. */
    private final Map<String, String> liveByFunction = new HashMap<>();
    private final Deque<String> liveIdsOrder = new ArrayDeque<>();
    private final Set<String> everAdmitted = new HashSet<>();
    private final Map<String, TicketId> ticketIdById = new HashMap<>();
    private final StringBuilder opLog = new StringBuilder();

    private long sequenceCursor;
    private int idCounter;

    @Test
    void tenThousandRandomOperationsPreserveWorkConservationIdempotenceAndInvariants() {
        Random random = new Random(SEED);
        PerFunctionSchedulingStrategy perFunction = new PerFunctionSchedulingStrategy();
        SharedQueueSchedulingStrategy sharedQueue = new SharedQueueSchedulingStrategy();
        StrategyRegistry registry = new StrategyRegistry(List.of(perFunction, sharedQueue));

        EngineReadiness readiness = mock(EngineReadiness.class);
        when(readiness.runnable(any())).thenAnswer(inv -> {
            FunctionGeneration generation = inv.getArgument(0);
            return runnableByFunction.getOrDefault(generation.functionName(), true);
        });
        EngineDispatch dispatch = mock(EngineDispatch.class);
        when(dispatch.isCurrent(any())).thenReturn(true);
        DispatchOwnership lease = mock(DispatchOwnership.class);
        when(dispatch.tryAcquire(any())).thenReturn(lease);
        doAnswer(inv -> {
            String id = ((InvocationTask) inv.getArgument(0)).executionId();
            submitted.add(id);
            assertTrue(submittedIds.add(id), "a ticket was submitted twice: " + id);
            return null;
        }).when(dispatch).submit(any());
        doAnswer(inv -> {
            String id = ((InvocationTask) inv.getArgument(0)).executionId();
            assertTrue(expiredIds.add(id), "a ticket expired twice: " + id);
            return null;
        }).when(dispatch).expired(any());
        doAnswer(inv -> {
            String id = ((InvocationTask) inv.getArgument(0)).executionId();
            assertTrue(removedIds.add(id), "a ticket was removed twice: " + id);
            return null;
        }).when(dispatch).removed(any());

        PendingWorkStore store = new PendingWorkStore(100_000);
        SchedulerEngine engine = new SchedulerEngine(store, registry, "per-function",
                dispatch, readiness, clock, () -> 0L);

        try {
            for (int op = 0; op < OPERATIONS; op++) {
                applyRandomOperation(random, engine);
                checkInvariants(engine, store, op);
            }
            checkWorkConservation(store);
        } catch (AssertionError | RuntimeException failure) {
            throw new AssertionError("Model test failed with seed " + SEED
                    + " after operation log:\n" + opLog, failure);
        }
    }

    private void applyRandomOperation(Random random, SchedulerEngine engine) {
        int choice = random.nextInt(7);
        switch (choice) {
            case 0 -> enqueue(random, engine);
            case 1 -> removeLive(random, engine);
            case 2 -> tick(engine);
            case 3 -> advanceClock(random);
            case 4 -> changeCapacity(random);
            case 5 -> switchStrategy(random, engine);
            default -> completeIdempotenceProbe(random, engine);
        }
    }

    private void enqueue(Random random, SchedulerEngine engine) {
        String function = FUNCTIONS[random.nextInt(FUNCTIONS.length)];
        String id = "op-" + idCounter++;
        boolean delayed = random.nextInt(10) == 0;
        boolean hasDeadline = random.nextInt(5) == 0;
        Instant now = clock.instant();
        Instant notBefore = delayed ? now.plusSeconds(1 + random.nextInt(120)) : now;
        Instant deadline = hasDeadline ? now.plusSeconds(random.nextInt(60) - 30) : null;
        TicketId ticketId = new TicketId(id, 1);
        SchedulingTicket ticket = new SchedulingTicket(ticketId,
                new FunctionGeneration(function, 1), sequenceCursor++, now, notBefore, deadline);
        InvocationTask task = new InvocationTask(id, function, null, null, null, null, now, 1,
                InvocationKind.ASYNC);
        boolean admitted = engine.enqueue(new PendingEntry(ticket, task));
        log("enqueue(" + id + ", fn=" + function + ", notBefore=+" + (notBefore.getEpochSecond()
                - now.getEpochSecond()) + "s, deadline=" + deadline + ") -> " + admitted);
        if (admitted) {
            liveByFunction.put(id, function);
            liveIdsOrder.addLast(id);
            everAdmitted.add(id);
            ticketIdById.put(id, ticketId);
        }
    }

    private void removeLive(Random random, SchedulerEngine engine) {
        if (liveIdsOrder.isEmpty()) {
            log("remove(none live) -> skipped");
            return;
        }
        List<String> live = new ArrayList<>(liveIdsOrder);
        String id = live.get(random.nextInt(live.size()));
        engine.remove(ticketIdById.get(id));
        log("remove(" + id + ")");
        // The engine settles synchronously here (no worker thread, no in-flight submit), so the
        // ticket is either freshly removed (reported through dispatch.removed, already asserted
        // for double-firing above) or was already gone. Either way it must leave the live model.
        liveByFunction.remove(id);
        liveIdsOrder.remove(id);
    }

    private void tick(SchedulerEngine engine) {
        engine.tick();
        log("tick()");
        // Reconcile the model against whatever the tick just settled: a ticket now in
        // submitted/expired/removed leaves the live set.
        liveIdsOrder.removeIf(id -> {
            boolean settled = submittedIds.contains(id) || expiredIds.contains(id) || removedIds.contains(id);
            if (settled) {
                liveByFunction.remove(id);
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

    private void switchStrategy(Random random, SchedulerEngine engine) {
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
     * The brief's "complete" operation: exercised here as an idempotence probe against ids that
     * are not currently live (already terminal, or never admitted at all). The engine's own
     * remove() contract requires this to be a silent no-op — see {@link SchedulingIndex} javadoc
     * ("remove is idempotent") and {@link SchedulerEngine#remove}.
     */
    private void completeIdempotenceProbe(Random random, SchedulerEngine engine) {
        String id;
        if (!everAdmitted.isEmpty() && random.nextBoolean()) {
            List<String> all = new ArrayList<>(everAdmitted);
            id = all.get(random.nextInt(all.size()));
        } else {
            id = "never-admitted-" + random.nextInt(1_000_000);
        }
        if (liveByFunction.containsKey(id)) {
            // Already covered by removeLive; skip to keep this probe specifically about
            // already-terminal or unknown ids.
            log("complete(" + id + ") -> skipped, still live");
            return;
        }
        engine.remove(ticketIdById.getOrDefault(id, new TicketId(id, 1)));
        log("complete(" + id + ") -> idempotent no-op");
    }

    private void checkInvariants(SchedulerEngine engine, PendingWorkStore store, int op) {
        assertThat(store.claimedCount())
                .as("op %d: claimed must always be empty between operations (no worker thread here)", op)
                .isZero();
        assertThat(store.submittingCount())
                .as("op %d: submitting must always be empty between operations", op)
                .isZero();
        assertThat(store.pendingCount())
                .as("op %d: pendingCount must match the model's live ticket count", op)
                .isEqualTo(liveIdsOrder.size());
        Map<String, Long> liveCountByFunction = new HashMap<>();
        for (String function : liveByFunction.values()) {
            liveCountByFunction.merge(function, 1L, Long::sum);
        }
        for (String function : FUNCTIONS) {
            long expected = liveCountByFunction.getOrDefault(function, 0L);
            assertThat((long) engine.reservedCount(function))
                    .as("op %d: reservedCount(%s) must match the model's live count for it", op, function)
                    .isEqualTo(expected);
        }
        // No id is ever in two terminal sets, nor both live and terminal (idempotence +
        // work conservation, checked incrementally rather than only at the end).
        for (String id : liveByFunction.keySet()) {
            assertThat(submittedIds).as("op %d: %s live and submitted", op, id).doesNotContain(id);
            assertThat(removedIds).as("op %d: %s live and removed", op, id).doesNotContain(id);
            assertThat(expiredIds).as("op %d: %s live and expired", op, id).doesNotContain(id);
        }
    }

    private void checkWorkConservation(PendingWorkStore store) {
        long terminal = (long) submittedIds.size() + removedIds.size() + expiredIds.size();
        assertThat(terminal + liveIdsOrder.size())
                .as("every admitted ticket must end up live, submitted, removed or expired — never "
                        + "more than one and never zero")
                .isEqualTo(everAdmitted.size());
        assertThat(store.pendingCount()).isEqualTo(liveIdsOrder.size());
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
