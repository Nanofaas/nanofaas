package it.unimib.datai.nanofaas.modules.asyncqueue;

import static org.assertj.core.api.Assertions.assertThat;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PerFunctionSchedulingStrategyTest {

    private static SchedulingTicket ticket(String id, String function, long sequence) {
        Instant now = Instant.parse("2026-09-16T10:00:00Z");
        return new SchedulingTicket(new TicketId(id, 1), new FunctionGeneration(function, 1),
                sequence, now, now, now.plusSeconds(60));
    }

    @Test
    void id_isPerFunction() {
        assertThat(new PerFunctionSchedulingStrategy().id()).isEqualTo("per-function");
    }

    /**
     * The turn rotation and its bound. Task 13b (issue #208) retired three loop-driven tests whose
     * property is this one: {@code AsyncSchedulerFairnessPerfTest
     * .asyncScheduler_hotFunctionDoesNotStarveSecondFunction} (a hot function with three queued
     * tickets must not hold the turn past the batch while a second function waits),
     * {@code SchedulerResilienceTest
     * .scheduler_requeuesFunctionAfterBoundedBatchInsteadOfDrainingWholeBurst} and
     * {@code AsyncQueueDiagnosticsTest}'s batch-limit assertion. All three built the deleted
     * {@code modules.asyncqueue.Scheduler}; the trace they observed is asserted here directly on
     * the index, and the blocked variant of the same fairness property is
     * {@link #blockedFunctionDoesNotHideReadyWork}.
     */
    @Test
    void turnMovesToNextFunctionAfterMaxBatchPerFunctionDispatches() {
        SchedulingIndex index = new PerFunctionSchedulingStrategy().newIndex();
        Instant now = Instant.parse("2026-09-16T10:00:01Z");

        index.add(ticket("a0", "a", 0));
        index.add(ticket("a1", "a", 1));
        index.add(ticket("a2", "a", 2));
        index.add(ticket("b0", "b", 3));

        SchedulingTicket first = index.select(now, generation -> true);
        assertThat(first.id()).isEqualTo(new TicketId("a0", 1));
        index.remove(first.id());

        SchedulingTicket second = index.select(now, generation -> true);
        assertThat(second.id()).isEqualTo(new TicketId("a1", 1));
        index.remove(second.id());

        // Batch of two A dispatches consumed: the turn moves to B even though A still
        // has a queued ticket (a2).
        SchedulingTicket third = index.select(now, generation -> true);
        assertThat(third.id()).isEqualTo(new TicketId("b0", 1));
        index.remove(third.id());

        SchedulingTicket fourth = index.select(now, generation -> true);
        assertThat(fourth.id()).isEqualTo(new TicketId("a2", 1));

        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void blockedFunctionDoesNotHideReadyWork() {
        SchedulingIndex index = new PerFunctionSchedulingStrategy().newIndex();
        index.add(ticket("a1", "blocked", 0));
        index.add(ticket("b1", "ready", 1));

        SchedulingTicket selected = index.select(Instant.parse("2026-09-16T10:00:01Z"),
                generation -> generation.functionName().equals("ready"));

        assertThat(selected.id()).isEqualTo(new TicketId("b1", 1));
        assertThat(index.size()).isEqualTo(2);
        index.remove(selected.id());
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void removeIsIdempotentAndClearResetsState() {
        SchedulingIndex index = new PerFunctionSchedulingStrategy().newIndex();
        index.add(ticket("a0", "a", 0));
        TicketId id = new TicketId("a0", 1);

        index.remove(id);
        index.remove(id);
        assertThat(index.size()).isZero();

        index.add(ticket("a0", "a", 0));
        index.clear();
        assertThat(index.size()).isZero();
        assertThat(index.select(Instant.parse("2026-09-16T10:00:01Z"), generation -> true)).isNull();
    }
}
