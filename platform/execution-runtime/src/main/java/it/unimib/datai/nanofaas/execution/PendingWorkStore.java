package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The engine's authoritative store of pending work and its queue reservations. A
 * {@link TicketId} occupies exactly one reservation slot in this store from the moment it is
 * offered until it is either removed or finishes submitting: claiming it for dispatch does not
 * free that slot, and neither does moving a claim into {@code submitting}. Only
 * {@link #finishSubmit} and {@link #remove} (of a pending or claimed ticket) release it.
 *
 * <p>This store does not call listeners, does not close leases and does not impose a second
 * payload cap: {@code InvocationCapacity} and {@code queuedInputLease} remain authoritative for
 * those budgets. The engine serializes all operations on this store; it does not lock
 * internally. Two reads are taken outside the gate: {@link #reservedCount()} and
 * {@link #reservedCount(String)}, which gauges read concurrently and which therefore live in a
 * concurrent map and a volatile total written only by the engine's serialized mutations; and the
 * admission estimate {@link #pendingCount()}, which is a momentary snapshot (see its note).
 */
public final class PendingWorkStore {

    private final int maxPending;
    private final Map<TicketId, PendingEntry> entries = new LinkedHashMap<>();
    private final Set<TicketId> claimed = new LinkedHashSet<>();
    private final Set<TicketId> submitting = new LinkedHashSet<>();
    private final ConcurrentHashMap<String, Integer> reservedByFunction = new ConcurrentHashMap<>();
    // Non-atomic ++/-- on a volatile is safe here: there is exactly one writer at a time (every
    // mutation runs under the engine's gate); volatile only publishes the value to gauge readers.
    private volatile int reserved;

    public PendingWorkStore(int maxPending) {
        if (maxPending <= 0) {
            throw new IllegalArgumentException("maxPending must be positive, was " + maxPending);
        }
        this.maxPending = maxPending;
    }

    /**
     * Admits a new entry under its ticket id's reservation. Rejected when the ticket id already
     * occupies a reservation (pending, claimed or submitting) or when the full map is already at
     * capacity.
     */
    public boolean offer(PendingEntry entry) {
        TicketId id = entry.ticket().id();
        if (entries.containsKey(id)) {
            return false;
        }
        if (entries.size() >= maxPending) {
            return false;
        }
        entries.put(id, entry);
        reservedByFunction.merge(entry.ticket().generation().functionName(), 1, Integer::sum);
        reserved++;
        return true;
    }

    public PendingEntry get(TicketId id) {
        return entries.get(id);
    }

    /**
     * Adds {@code id} to the claimed set; the entry and its reservation are untouched. A ticket
     * already in {@code submitting} cannot be claimed again — its reservation is already
     * committed to a dispatch in flight.
     */
    public PendingEntry claim(TicketId id) {
        PendingEntry entry = entries.get(id);
        return entry != null && !submitting.contains(id) && claimed.add(id) ? entry : null;
    }

    /** Reverses a claim, leaving the entry pending with its reservation intact. */
    public void abort(TicketId id) {
        claimed.remove(id);
    }

    /**
     * Moves a claim into {@code submitting}, keeping both the entry and its reservation.
     *
     * @throws IllegalStateException if {@code id} was not claimed
     */
    public PendingEntry commit(TicketId id) {
        if (!claimed.remove(id)) {
            throw new IllegalStateException("ticket not claimed: " + id);
        }
        submitting.add(id);
        return entries.get(id);
    }

    /** Drops the entry and its reservation once its dispatch has been submitted. */
    public void finishSubmit(TicketId id) {
        submitting.remove(id);
        releaseReservation(entries.remove(id));
    }

    /** Clears the submitting state, leaving the entry pending for another submit attempt. */
    public void requeueSubmit(TicketId id) {
        submitting.remove(id);
    }

    /**
     * Removes a pending or claimed ticket, returning its entry so the caller can clean up
     * outside the gate. A ticket already in {@code submitting} is left untouched and {@code null}
     * is returned: that invocation is already committed and its cancellation belongs to the
     * lifecycle, not to this store.
     */
    public PendingEntry remove(TicketId id) {
        if (submitting.contains(id)) {
            return null;
        }
        claimed.remove(id);
        PendingEntry entry = entries.remove(id);
        releaseReservation(entry);
        return entry;
    }

    /** Outstanding reservations: pending, claimed and submitting. Safe to read without the gate. */
    public int reservedCount() {
        return reserved;
    }

    /** Outstanding reservations of one function name; zero when it has none. Safe to read
     * without the gate. */
    public int reservedCount(String functionName) {
        return reservedByFunction.getOrDefault(functionName, 0);
    }

    /** A function's entry is pruned at zero, so idle and removed names are not retained. */
    private void releaseReservation(PendingEntry entry) {
        if (entry == null) {
            return;
        }
        reservedByFunction.compute(entry.ticket().generation().functionName(),
                (name, count) -> count == 1 ? null : count - 1);
        reserved--;
    }

    /** All reservations, including provisional claims and submits that may requeue. */
    public List<PendingEntry> snapshotAll() {
        return List.copyOf(entries.values());
    }

    /** Pending entries only — excludes claimed and submitting — ordered by ticket sequence. */
    public List<PendingEntry> snapshotPending() {
        return entries.values().stream()
                .filter(entry -> !claimed.contains(entry.ticket().id())
                        && !submitting.contains(entry.ticket().id()))
                .sorted(Comparator.comparingLong(entry -> entry.ticket().sequence()))
                .toList();
    }

    /**
     * The pending count: entries that are neither claimed nor submitting. Not the reservation
     * count — {@link #reservedCount()} also includes claims and submits.
     *
     * <p>Like every read here this is unprotected, and unlike the others it has one caller that
     * deliberately takes it OUTSIDE the engine's gate —
     * {@code EngineSyncQueueGateway.doEnqueueOrThrow}'s admission estimate, which has to run
     * before it can ask the engine to admit. A concurrent engine pass mutating the maps therefore
     * makes this a momentary snapshot rather than a linearized one: it can be a few entries stale,
     * and its three terms are not read atomically. That is tolerable because nothing is decided on
     * it — it feeds an admission THRESHOLD, and the binding cap is {@link #offer}'s, which runs
     * under the gate and is exact. Do not promote this read into an authority: reserve against
     * {@link #offer}'s answer, never against this estimate.
     */
    public int pendingCount() {
        return entries.size() - claimed.size() - submitting.size();
    }

    public int claimedCount() {
        return claimed.size();
    }

    public int submittingCount() {
        return submitting.size();
    }
}
