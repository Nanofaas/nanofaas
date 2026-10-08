package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;

import java.util.Objects;

/**
 * One unit of pending work held by {@link PendingWorkStore}: the scheduling ticket (identity,
 * ordering, timing) paired with the {@link InvocationTask} the engine will actually dispatch.
 * The ticket travels through {@link it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex};
 * the task and its leases never do.
 *
 * @param ticket the index-visible identity and ordering for this piece of work
 * @param task   the payload the engine dispatches once this ticket is selected
 */
public record PendingEntry(SchedulingTicket ticket, InvocationTask task) {

    public PendingEntry {
        Objects.requireNonNull(ticket, "ticket must not be null");
        Objects.requireNonNull(task, "task must not be null");
    }
}
