package it.unimib.datai.nanofaas.controlplane.scheduler;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;

import java.time.Instant;
import java.util.function.Predicate;

/**
 * The mutable, in-memory ordering structure of one scheduling policy. An index holds
 * tickets only — no payload, no threads, no meters and no callbacks — and is confined to
 * the engine's own gate: every method here is called under the engine's serialization,
 * never concurrently with itself.
 *
 * <p><strong>{@link #select} is a pure scan.</strong> It returns the first runnable ticket
 * (or {@code null} if none is runnable right now) and mutates nothing: it does not remove
 * the ticket from the index and does not rotate any internal ordering. This mirrors the
 * existing schedulers this contract replaces: {@code SyncScheduler} selects with
 * {@code queue.peekReady(now)}, which does not mutate, and rotates only on the failure
 * paths via {@code queue.rotateReadyScanWindow(now)} / {@code queue.rotateReadyItem(item, now)}.
 *
 * <p>{@link #defer} is the only method that applies a policy's rotation (round-robin turn
 * advance, scan-window rotation, etc.). The engine calls {@code defer} when a selected
 * ticket could not be dispatched (no capacity, lease unavailable, ...) so the policy gets a
 * chance to make progress on the next scan without re-selecting the same blocked ticket.
 *
 * <p>The {@code runnable} predicate reads only already-prepared in-memory observations
 * (e.g. current replica/capacity state for a generation); it must never call a provider, a
 * store or a mutable registry. {@link #remove} is idempotent — removing an id that is not
 * present is a no-op, not an error.
 */
public interface SchedulingIndex {

    /**
     * Admits a ticket into the index. Implementations reject duplicate ids before mutating.
     */
    void add(SchedulingTicket ticket);

    /**
     * Removes the ticket with the given id, if present. Idempotent: removing an absent id
     * does no harm and does not throw.
     */
    void remove(TicketId id);

    /**
     * Pure scan for the first ticket eligible for dispatch: not before {@code now} and whose
     * generation is accepted by {@code runnable}. Returns {@code null} when none qualifies.
     * Never mutates the index — see the type-level javadoc.
     */
    SchedulingTicket select(Instant now, Predicate<FunctionGeneration> runnable);

    /**
     * Applies the policy's rotation for the given ticket, e.g. because it was selected but
     * could not be dispatched. This is the only method that changes selection order.
     */
    void defer(TicketId id);

    /**
     * Number of tickets currently held by the index.
     */
    int size();

    /**
     * Removes every ticket the index holds, including any generation/id bookkeeping.
     */
    void clear();
}
