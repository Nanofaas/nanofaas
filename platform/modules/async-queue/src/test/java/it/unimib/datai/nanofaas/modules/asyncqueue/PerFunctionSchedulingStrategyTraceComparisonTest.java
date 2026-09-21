package it.unimib.datai.nanofaas.modules.asyncqueue;

import static org.assertj.core.api.Assertions.assertThat;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link PerFunctionSchedulingStrategy} to the selection order of the pre-refactor
 * {@code modules.asyncqueue.Scheduler} loop on a corpus that exercises every event kind the
 * plan mandates: publish, a function going blocked then unblocked mid-stream, dispatches
 * interleaved across three functions (so turn/batch accounting is live), and a standalone
 * removal that is not a dispatch.
 *
 * <h2>Why the expected trace is a literal and not a live comparison</h2>
 *
 * <p>Until Task 13b this test built the real {@code Scheduler} and asserted the index's order
 * equalled <em>its</em> order, so the old loop was the oracle. Task 13b deleted that loop
 * (issue #208: the composed {@code SchedulerEngine} owns the selection now), so the oracle is
 * gone and the equivalence is pinned by the trace the old loop produced, recorded here as
 * {@link #OLD_LOOP_TRACE}. The value that made the live comparison worth having — a fixed,
 * non-trivial reference order that a retune of the turn/batch bookkeeping would break — is kept;
 * what is lost is only the ability to re-derive that order from the deleted class, which no
 * longer exists to retune. The literal was the last observed output of the deleted test's old
 * arm at {@code 25388b1a} ({@code git show 25388b1a:platform/modules/async-queue/src/test/java/
 * it/unimib/datai/nanofaas/modules/asyncqueue/PerFunctionSchedulingStrategyTraceComparisonTest.java}),
 * and the corpus below is unchanged from that revision.
 *
 * <p>Function {@code a} has concurrency 1 with three queued tickets: dispatching the first
 * exhausts its only slot, so the second attempt in the same turn blocks — the old loop's
 * {@code Scheduler.processFunction} hit a failed {@code tryAcquireLease}, ended the visit and did
 * not re-signal because {@code canDispatch()} was false. Releasing that lease unblocked {@code a}
 * and let it resume — twice, since concurrency 1 means every dispatch blocks the next one.
 * Functions {@code b} (three tickets) and {@code c} (two tickets) have ample concurrency and
 * dispatch normally, including one full batch-of-2 turn each, so turn/batch bookkeeping is
 * exercised on the live path too. Function {@code d} is enqueued and then removed before the
 * loop ever runs — a removal with no dispatch at all.
 */
class PerFunctionSchedulingStrategyTraceComparisonTest {

    /**
     * The pre-refactor {@code Scheduler}'s dispatch order on the corpus below, recorded before
     * that class was deleted in Task 13b. Read-only: do not retune to match a changed index.
     */
    private static final List<String> OLD_LOOP_TRACE =
            List.of("a0", "b0", "b1", "c0", "c1", "b2", "a1", "a2");

    @Test
    void theIndexReproducesThePreRefactorLoopTraceOnFullCorpus() {
        SchedulingIndex index = new PerFunctionSchedulingStrategy().newIndex();
        Instant now = Instant.parse("2026-09-16T10:00:01Z");

        index.add(ticket("a0", "a", 0));
        index.add(ticket("b0", "b", 1));
        index.add(ticket("c0", "c", 2));
        index.add(ticket("a1", "a", 3));
        index.add(ticket("b1", "b", 4));
        index.add(ticket("c1", "c", 5));
        index.add(ticket("a2", "a", 6));
        index.add(ticket("b2", "b", 7));
        index.add(ticket("d0", "d", 8));

        // The same standalone removal the old run performed up front, before the loop started -
        // a removal that is not, and never becomes, a dispatch.
        index.remove(new TicketId("d0", 1));
        assertThat(index.size()).isEqualTo(8);

        // "a" has concurrency 1 in the old run: at most one of its tickets is ever in flight,
        // exactly like the real DispatchOwnership lease. aInFlight models that lease count -
        // incremented the instant an "a" ticket is removed (dispatched), decremented by an
        // explicit "release" between phases, mirroring the old run's dispatchLease().release().
        int[] aInFlight = {0};
        java.util.function.Predicate<FunctionGeneration> runnable =
                g -> !g.functionName().equals("a") || aInFlight[0] < 1;
        List<String> newOrder = new ArrayList<>();

        drainWhileRunnable(index, now, runnable, newOrder, aInFlight);
        // "a" is now blocked (its only lease is held by a0), matching the old run's stable
        // 6-dispatch plateau.
        assertThat(index.select(now, runnable)).isNull();

        aInFlight[0]--; // unblock #1: release a0's lease
        drainWhileRunnable(index, now, runnable, newOrder, aInFlight);
        assertThat(index.select(now, runnable)).isNull();

        aInFlight[0]--; // unblock #2: release a1's lease
        drainWhileRunnable(index, now, runnable, newOrder, aInFlight);

        assertThat(newOrder).containsExactlyElementsOf(OLD_LOOP_TRACE);
        assertThat(newOrder).doesNotContain("d0");
    }

    /**
     * Selects and removes until nothing more is runnable, incrementing {@code aInFlight}
     * whenever an "a" ticket is dispatched - the moment its one lease slot is taken.
     */
    private static void drainWhileRunnable(SchedulingIndex index, Instant now,
            java.util.function.Predicate<FunctionGeneration> runnable, List<String> order,
            int[] aInFlight) {
        SchedulingTicket ticket;
        while ((ticket = index.select(now, runnable)) != null) {
            order.add(ticket.id().executionId());
            index.remove(ticket.id());
            if (ticket.generation().functionName().equals("a")) {
                aInFlight[0]++;
            }
        }
    }

    private static SchedulingTicket ticket(String id, String function, long sequence) {
        Instant now = Instant.parse("2026-09-16T10:00:00Z");
        return new SchedulingTicket(new TicketId(id, 1), new FunctionGeneration(function, 1),
                sequence, now, now, now.plusSeconds(60));
    }
}
