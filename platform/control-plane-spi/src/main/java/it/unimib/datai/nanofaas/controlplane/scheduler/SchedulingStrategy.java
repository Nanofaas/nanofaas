package it.unimib.datai.nanofaas.controlplane.scheduler;

/**
 * A scheduling policy: a stable identifier and a factory for fresh, empty indexes that
 * implement it. A strategy holds no state of its own — each call to {@link #newIndex()}
 * produces an independent {@link SchedulingIndex}, which is what the engine builds and
 * swaps when switching policies at runtime.
 */
public interface SchedulingStrategy {

    /**
     * Stable identifier for this policy (e.g. {@code "per-function"}, {@code "shared-queue"}).
     * Used verbatim in admin APIs and runtime-config selection.
     */
    String id();

    /**
     * Creates a new, empty index implementing this policy.
     */
    SchedulingIndex newIndex();
}
