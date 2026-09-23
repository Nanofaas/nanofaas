package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchedulingTicketTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");
    private static final FunctionGeneration GENERATION = new FunctionGeneration("echo", 1);

    @Test void ticketHasNoPayloadAndKeepsAttemptIdentity() {
        var generation = new FunctionGeneration("echo", 1);
        var now = Instant.parse("2026-09-16T10:00:00Z");
        var ticket = new SchedulingTicket(new TicketId("e1", 1), generation,
                0, now, now.plusSeconds(2), now.plusSeconds(30));
        assertThat(ticket.id()).isNotEqualTo(new TicketId("e1", 2));
        assertThat(ticket.notBefore()).isEqualTo(now.plusSeconds(2));
        assertThat(Arrays.stream(SchedulingTicket.class.getRecordComponents())
                .map(RecordComponent::getType)).doesNotContain(
                        InvocationTask.class, InvocationRequest.class, CompletableFuture.class);
    }

    @Test void ticketAcceptsNullQueueDeadlineButNotNullNotBefore() {
        var ticket = new SchedulingTicket(new TicketId("e1", 1), GENERATION, 0, NOW, NOW, null);
        assertThat(ticket.queueDeadline()).isNull();
        assertThatThrownBy(() -> new SchedulingTicket(new TicketId("e1", 1), GENERATION, 0, NOW, null, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test void ticketRejectsNullIdGenerationOrEnqueuedAt() {
        assertThatThrownBy(() -> new SchedulingTicket(null, GENERATION, 0, NOW, NOW, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SchedulingTicket(new TicketId("e1", 1), null, 0, NOW, NOW, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SchedulingTicket(new TicketId("e1", 1), GENERATION, 0, null, NOW, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test void ticketRejectsNegativeSequence() {
        assertThatThrownBy(() -> new SchedulingTicket(new TicketId("e1", 1), GENERATION, -1, NOW, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void ticketIdRejectsBlankExecutionIdOrNonPositiveAttempt() {
        assertThatThrownBy(() -> new TicketId(null, 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TicketId("", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TicketId("e1", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TicketId("e1", -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void schedulerSelectionCopiesAvailableList() {
        var mutable = new java.util.ArrayList<>(List.of("per-function", "shared-queue"));
        var selection = new it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerSelection(
                "per-function", mutable, "restart");
        mutable.add("mutated-after");
        assertThat(selection.available()).containsExactly("per-function", "shared-queue");
        assertThatThrownBy(() -> selection.available().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void schedulerSelectionRejectsNulls() {
        assertThatThrownBy(() -> new it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerSelection(
                null, List.of("per-function"), "restart")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerSelection(
                "per-function", null, "restart")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerSelection(
                "per-function", List.of("per-function"), null)).isInstanceOf(NullPointerException.class);
    }
}
