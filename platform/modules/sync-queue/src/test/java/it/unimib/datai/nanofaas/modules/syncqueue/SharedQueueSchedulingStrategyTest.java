package it.unimib.datai.nanofaas.modules.syncqueue;

import static org.assertj.core.api.Assertions.assertThat;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SharedQueueSchedulingStrategyTest {

    private static SchedulingTicket ticket(String id, String function, long sequence) {
        Instant now = Instant.parse("2026-09-16T10:00:00Z");
        return new SchedulingTicket(new TicketId(id, 1), new FunctionGeneration(function, 1),
                sequence, now, now, now.plusSeconds(60));
    }

    @Test
    void id_isSharedQueue() {
        assertThat(new SharedQueueSchedulingStrategy().id()).isEqualTo("shared-queue");
    }

    @Test
    void blockedFunctionDoesNotHideReadyWork() {
        SchedulingIndex index = new SharedQueueSchedulingStrategy().newIndex();
        index.add(ticket("a1", "blocked", 0));
        index.add(ticket("b1", "ready", 1));
        var selected = index.select(Instant.parse("2026-09-16T10:00:01Z"),
                generation -> generation.functionName().equals("ready"));
        assertThat(selected.id()).isEqualTo(new TicketId("b1", 1));
        assertThat(index.size()).isEqualTo(2);
        index.remove(selected.id());
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void scanWindowIsBoundedAndDeferRotatesUnreachableReadyTicketIntoRange() {
        SchedulingIndex index = new SharedQueueSchedulingStrategy().newIndex();
        Instant now = Instant.parse("2026-09-16T10:00:01Z");
        for (int i = 0; i < 65; i++) {
            index.add(ticket("blocked-" + i, "blocked", i));
        }
        index.add(ticket("ready", "ready", 65));

        // The ready ticket sits at position 66: one visit scans at most 64 entries and
        // must not find it, even though it is otherwise eligible.
        SchedulingTicket notFound = index.select(now, generation -> generation.functionName().equals("ready"));
        assertThat(notFound).isNull();

        // Rotating the bounded window (defer, as the engine does after a failed selection)
        // makes the ready ticket reachable on the next scan.
        index.defer(new TicketId("blocked-0", 1));

        SchedulingTicket found = index.select(now, generation -> generation.functionName().equals("ready"));
        assertThat(found).isNotNull();
        assertThat(found.id()).isEqualTo(new TicketId("ready", 1));
        assertThat(index.size()).isEqualTo(66);
    }

    @Test
    void removeIsIdempotentAndClearResetsState() {
        SchedulingIndex index = new SharedQueueSchedulingStrategy().newIndex();
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
