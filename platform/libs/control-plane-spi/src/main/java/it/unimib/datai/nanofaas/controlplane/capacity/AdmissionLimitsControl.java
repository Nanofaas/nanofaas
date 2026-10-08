package it.unimib.datai.nanofaas.controlplane.capacity;

/**
 * The admission limits a runtime-configuration extension may read and replace while serving.
 *
 * <p>The limits are exchanged as plain values, so the mutable capacity objects that own them stay
 * in the core: an extension proposes numbers, and the core applies them to the live quotas. Every
 * value is a positive count or byte budget, and a per-function value never exceeds its global
 * counterpart — the implementation rejects a snapshot that breaks either rule, so a reduction
 * cannot be smuggled in as an unbounded value.</p>
 *
 * <p>Reducing a limit below current occupancy is allowed and does not evict work in flight; it
 * stops new admissions until occupancy falls back inside the limit.</p>
 */
public interface AdmissionLimitsControl {

    /** A global budget paired with its per-function share. */
    record Pair(long global, long perFunction) {
    }

    /** The complete set of hot-updatable admission limits, always read and written as a whole. */
    record Snapshot(int rateMaxPerSecond,
                    Pair executions,
                    Pair canonicalInputBytes,
                    Pair physicalInputCopyBytes,
                    Pair waiters) {
    }

    /** The limits currently in force. */
    Snapshot limits();

    /** Replaces every limit at once; a partially applied snapshot is not a supported outcome. */
    void updateLimits(Snapshot limits);
}
