package it.unimib.datai.nanofaas.controlplane.scheduler;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;

import java.time.Instant;
import java.util.Objects;

/**
 * A schedulable unit of work held by a {@link SchedulingIndex}: identity, ordering and
 * timing only. No payload, no {@link InvocationTask}, no request body, no future and no
 * callback travels with a ticket — those stay in the engine's pending store, keyed by
 * {@link #id()}.
 *
 * @param id            attempt identity
 * @param generation    the function incarnation this ticket targets
 * @param sequence      monotonic admission order within the index; non-negative
 * @param enqueuedAt    when the ticket was admitted
 * @param notBefore     earliest instant the ticket may be selected; always non-null
 * @param queueDeadline latest instant the ticket may wait in queue; may be null when the
 *                      profile defines no queue deadline
 */
public record SchedulingTicket(
        TicketId id,
        FunctionGeneration generation,
        long sequence,
        Instant enqueuedAt,
        Instant notBefore,
        Instant queueDeadline) {

    public SchedulingTicket {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(generation, "generation must not be null");
        Objects.requireNonNull(enqueuedAt, "enqueuedAt must not be null");
        Objects.requireNonNull(notBefore, "notBefore must not be null");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative, was " + sequence);
        }
    }
}
