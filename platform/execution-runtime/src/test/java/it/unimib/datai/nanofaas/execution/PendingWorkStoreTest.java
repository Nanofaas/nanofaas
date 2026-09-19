package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PendingWorkStoreTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");
    private static final FunctionGeneration GENERATION = new FunctionGeneration("echo", 1);

    private static SchedulingTicket ticket(String executionId, long sequence) {
        return new SchedulingTicket(new TicketId(executionId, 1), GENERATION, sequence, NOW, NOW, null);
    }

    @Test void claimStillConsumesItsQueueReservation() {
        var store = new PendingWorkStore(1);
        Instant now = Instant.parse("2026-09-16T10:00:00Z");
        var ticket = new SchedulingTicket(new TicketId("e1", 1),
                new FunctionGeneration("echo", 1), 0, now, now, null);
        assertThat(store.offer(new PendingEntry(ticket, mock(InvocationTask.class)))).isTrue();
        assertThat(store.claim(ticket.id())).isNotNull();
        var second = new SchedulingTicket(new TicketId("e2", 1), ticket.generation(),
                1, now, now, null);
        assertThat(store.offer(new PendingEntry(second, mock(InvocationTask.class)))).isFalse();
        store.abort(ticket.id());
        assertThat(store.pendingCount()).isEqualTo(1);
        assertThat(store.claimedCount()).isZero();
    }

    @Test void duplicateOfferOfSameTicketIdIsRejected() {
        var store = new PendingWorkStore(2);
        var t = ticket("e1", 0);
        assertThat(store.offer(new PendingEntry(t, mock(InvocationTask.class)))).isTrue();
        assertThat(store.offer(new PendingEntry(t, mock(InvocationTask.class)))).isFalse();
        assertThat(store.pendingCount()).isEqualTo(1);
    }

    @Test void removeDuringClaimDropsEntryAndReturnsIt() {
        var store = new PendingWorkStore(2);
        var t = ticket("e1", 0);
        var task = mock(InvocationTask.class);
        store.offer(new PendingEntry(t, task));
        assertThat(store.claim(t.id())).isNotNull();

        var removed = store.remove(t.id());

        assertThat(removed).isNotNull();
        assertThat(removed.task()).isSameAs(task);
        assertThat(store.get(t.id())).isNull();
        assertThat(store.claimedCount()).isZero();
        assertThat(store.pendingCount()).isZero();
    }

    @Test void doubleAbortIsHarmless() {
        var store = new PendingWorkStore(2);
        var t = ticket("e1", 0);
        store.offer(new PendingEntry(t, mock(InvocationTask.class)));
        store.claim(t.id());

        store.abort(t.id());
        store.abort(t.id());

        assertThat(store.claimedCount()).isZero();
        assertThat(store.pendingCount()).isEqualTo(1);
    }

    @Test void commitWithoutClaimThrows() {
        var store = new PendingWorkStore(2);
        var t = ticket("e1", 0);
        store.offer(new PendingEntry(t, mock(InvocationTask.class)));

        assertThatThrownBy(() -> store.commit(t.id())).isInstanceOf(IllegalStateException.class);
    }

    @Test void removeOfOldGenerationEntryDropsItRegardlessOfState() {
        var store = new PendingWorkStore(2);
        var stale = ticket("e1", 0);
        store.offer(new PendingEntry(stale, mock(InvocationTask.class)));

        var removed = store.remove(stale.id());

        assertThat(removed).isNotNull();
        assertThat(removed.ticket()).isSameAs(stale);
        assertThat(store.pendingCount()).isZero();
        assertThat(store.get(stale.id())).isNull();
    }

    @Test void snapshotPendingExcludesClaimedAndSubmittingAndDoesNotReleaseThem() {
        var store = new PendingWorkStore(3);
        var pending = ticket("e1", 0);
        var claimed = ticket("e2", 1);
        var submitting = ticket("e3", 2);
        store.offer(new PendingEntry(pending, mock(InvocationTask.class)));
        store.offer(new PendingEntry(claimed, mock(InvocationTask.class)));
        store.offer(new PendingEntry(submitting, mock(InvocationTask.class)));
        store.claim(claimed.id());
        store.claim(submitting.id());
        store.commit(submitting.id());

        var snapshot = store.snapshotPending();

        assertThat(snapshot).extracting(PendingEntry::ticket).containsExactly(pending);
        // snapshot must not have released the claimed/submitting reservations.
        assertThat(store.claimedCount()).isEqualTo(1);
        assertThat(store.submittingCount()).isEqualTo(1);
        assertThat(store.get(claimed.id())).isNotNull();
        assertThat(store.get(submitting.id())).isNotNull();
    }

    @Test void commitMovesClaimToSubmittingAndKeepsEntryAndReservation() {
        var store = new PendingWorkStore(1);
        var t = ticket("e1", 0);
        store.offer(new PendingEntry(t, mock(InvocationTask.class)));
        store.claim(t.id());

        var committed = store.commit(t.id());

        assertThat(committed).isNotNull();
        assertThat(store.claimedCount()).isZero();
        assertThat(store.submittingCount()).isEqualTo(1);
        // reservation still occupied: cannot re-offer the same ticket id.
        assertThat(store.offer(new PendingEntry(ticket("e2", 1), mock(InvocationTask.class)))).isFalse();
    }

    @Test void removeDuringSubmittingReturnsNullAndLeavesLifecycleToOwnCancellation() {
        var store = new PendingWorkStore(2);
        var t = ticket("e1", 0);
        store.offer(new PendingEntry(t, mock(InvocationTask.class)));
        store.claim(t.id());
        store.commit(t.id());

        var removed = store.remove(t.id());

        assertThat(removed).isNull();
        assertThat(store.get(t.id())).isNotNull();
        assertThat(store.submittingCount()).isEqualTo(1);
    }

    @Test void finishSubmitDropsEntryAndReservation() {
        var store = new PendingWorkStore(1);
        var t = ticket("e1", 0);
        store.offer(new PendingEntry(t, mock(InvocationTask.class)));
        store.claim(t.id());
        store.commit(t.id());

        store.finishSubmit(t.id());

        assertThat(store.get(t.id())).isNull();
        assertThat(store.submittingCount()).isZero();
        assertThat(store.offer(new PendingEntry(ticket("e2", 1), mock(InvocationTask.class)))).isTrue();
    }

    @Test void claimOnSubmittingTicketReturnsNullAndLeavesClaimedCountUnchanged() {
        var store = new PendingWorkStore(1);
        var t = ticket("e1", 0);
        store.offer(new PendingEntry(t, mock(InvocationTask.class)));
        store.claim(t.id());
        store.commit(t.id());

        var reclaimed = store.claim(t.id());

        assertThat(reclaimed).isNull();
        assertThat(store.claimedCount()).isZero();
        assertThat(store.submittingCount()).isEqualTo(1);
    }

    @Test void snapshotPendingOrdersByAscendingSequenceRegardlessOfOfferOrder() {
        var store = new PendingWorkStore(3);
        var five = ticket("e5", 5);
        var one = ticket("e1", 1);
        var three = ticket("e3", 3);
        store.offer(new PendingEntry(five, mock(InvocationTask.class)));
        store.offer(new PendingEntry(one, mock(InvocationTask.class)));
        store.offer(new PendingEntry(three, mock(InvocationTask.class)));

        assertThat(store.snapshotPending()).extracting(PendingEntry::ticket)
                .containsExactly(one, three, five);
    }

    @Test void requeueSubmitClearsSubmittingButKeepsEntryPending() {
        var store = new PendingWorkStore(1);
        var t = ticket("e1", 0);
        store.offer(new PendingEntry(t, mock(InvocationTask.class)));
        store.claim(t.id());
        store.commit(t.id());

        store.requeueSubmit(t.id());

        assertThat(store.submittingCount()).isZero();
        assertThat(store.pendingCount()).isEqualTo(1);
        assertThat(store.snapshotPending()).extracting(PendingEntry::ticket).containsExactly(t);
    }
}
