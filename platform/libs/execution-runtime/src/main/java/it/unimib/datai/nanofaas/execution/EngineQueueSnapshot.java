package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;

import java.util.Map;

/**
 * A point-in-time view of {@link SchedulerEngine}'s pending work, taken under its own gate
 * ({@link SchedulerEngine#snapshotQueues()}). Immutable: {@code perGeneration} is copied
 * defensively so a caller cannot see it mutate out from under it, nor mutate it back into the
 * engine.
 *
 * <p>{@code pending} counts ready-and-delayed tickets only (excludes {@code claimed} and
 * {@code submitting}); {@code delayed} is a subset of {@code pending} — a ticket whose
 * {@code notBefore} has not yet arrived — and is never added a second time on top of
 * {@code pending}. The engine's queue reservations (one per admitted ticket, from admission
 * until dispatch settles) total {@code pending + claimed + submitting}; a ticket already
 * claimed or submitting belongs to the running-execution population once it is leased, not to
 * this queue view — do not add {@code submitting} back into an execution count derived
 * elsewhere.
 *
 * <p>{@code perGeneration} is keyed by {@link FunctionGeneration} for internal bookkeeping and
 * test assertions only. Per ADR 0001 §7, a generation is never exposed as a Prometheus tag —
 * nothing in this record may be published as metric label cardinality keyed by execution id,
 * ticket id or generation.
 *
 * @param pending       ready and delayed tickets, excluding claimed and submitting
 * @param claimed       tickets a selection has claimed but not yet committed to dispatch
 * @param submitting    tickets whose dispatch has committed and is in flight
 * @param delayed       the subset of {@code pending} not yet due ({@code notBefore} in the future)
 * @param perGeneration pending ticket count per generation, copied defensively
 */
public record EngineQueueSnapshot(int pending, int claimed, int submitting,
                                   int delayed, Map<FunctionGeneration, Integer> perGeneration) {

    public EngineQueueSnapshot {
        perGeneration = Map.copyOf(perGeneration);
    }
}
