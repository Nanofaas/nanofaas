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
 * <p>The engine calls {@link #advanceScanWindow} after a scan finds no candidate, and
 * {@link #defer} when a selected
 * ticket could not be dispatched (no capacity, lease unavailable, ...). The engine itself skips
 * that ticket's generation until its next wake, so an index needs no reordering to make
 * progress; the built-in indexes keep {@code defer} order-preserving.
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
     * Notifies the policy that the given ticket was selected but could not be dispatched. The
     * engine already excludes its generation until the next wake; reordering here is optional
     * and must not be needed for progress.
     */
    void defer(TicketId id);

    /**
     * Advances a bounded scan after no runnable candidate was found. Indexes that scan all
     * eligible heads need no rotation and can keep this default.
     */
    default void advanceScanWindow() {
    }

    /**
     * Number of tickets currently held by the index.
     */
    int size();

    /**
     * Removes every ticket the index holds, including any generation/id bookkeeping.
     */
    void clear();
}
