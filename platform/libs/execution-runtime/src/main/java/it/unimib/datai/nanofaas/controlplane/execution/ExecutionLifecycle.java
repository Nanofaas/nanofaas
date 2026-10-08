package it.unimib.datai.nanofaas.controlplane.execution;

/**
 * The single owner of an invocation's terminal transition.
 *
 * <p>An execution leaves the living in exactly one place: {@link #settle}. Before this
 * class existed the transition was split between collaborators: {@link ExecutionStore}
 * archived the outcome and invalidated the live record, and the idempotency key's move to
 * its terminal (tombstone) binding was one listener in a best-effort chain. An oversized
 * outcome evicted between those two steps left a still-{@code published} key that a replay
 * could re-claim, authorising a second execution for a function that had already run
 * (review finding R2). Making the key non-reclaimable is a correctness invariant, not a
 * notification: this class performs it as a first-class, ordered step that no listener can
 * skip, reorder or delay.
 *
 * <p>The transition, in order:
 *
 * <ol>
 *   <li><b>Dedup protection</b> — the key becomes non-reclaimable ({@code markTerminal})
 *       before the last live reference can disappear. Done under the record monitor so it is
 *       serialized against the admission path's publish, which is what closes the
 *       completion-before-publication ordering as well.</li>
 *   <li><b>Archive</b> — the outcome is retained when its weight fits; a declined payload
 *       still leaves the tombstone behind (I6 / I2).</li>
 *   <li><b>Live removal</b> — the mutable record is invalidated.</li>
 *   <li><b>Notification</b> — best-effort observers (metrics, end-to-end conclusion) run
 *       after the invariants hold; a throwing listener cannot interrupt cleanup or stop
 *       another owner from closing.</li>
 * </ol>
 *
 * <p>There is no global lock on the hot path: the record itself is the monitor, and no
 * listener, dispatcher or other external code runs while it is held. The {@link ExecutionStore}
 * keeps its {@code settle} method as the adapter the queue modules still call (their direct
 * use is retired in P20); when this owner is attached, that adapter delegates here.
 */
public class ExecutionLifecycle {
    private final ExecutionStore executionStore;
    private final IdempotencyStore idempotencyStore;

    public ExecutionLifecycle(ExecutionStore executionStore, IdempotencyStore idempotencyStore) {
        this.executionStore = executionStore;
        this.idempotencyStore = idempotencyStore;
        // Behind the store's public settle() adapter: from now on the terminal transition
        // is this owner's, and the store's own settle delegates to it.
        executionStore.attachLifecycle(this);
    }

    /**
     * The single terminal transition. Idempotent: a non-terminal record is ignored, a
     * terminal record whose transition already ran is a no-op at every step (the key is
     * already terminal, the archive put and the invalidation are idempotent, and
     * {@code CompletableFuture.complete} on a done future does nothing).
     *
     * <p>The record selects the definitive answer under its monitor. This owner
     * publishes that answer before archiving and notifying best-effort observers.
     */
    public void settle(ExecutionRecord executionRecord) {
        if (!executionRecord.isTerminal()) {
            return;
        }
        // 1. Dedup protection before the last live reference can disappear. The record
        // monitor is the same one publishAdmission uses, so a terminal record can never
        // be published under a reclaimable key.
        synchronized (executionRecord) { // NOSONAR (java:S2445): this object is its own monitor by design; every path locks the same instance
            protectKey(executionRecord);
            if (!executionRecord.beginSettlement()) return;
        }
        executionRecord.publishTerminal();
        // 2 + 3. Archive (optional) and live removal.
        executionStore.archiveAndRemove(executionRecord);
        // 4. Best-effort observers, after the invariants hold.
        executionStore.notifyTerminal(executionRecord);
    }

    /**
     * Moves the key to its tombstone while the record is still live. Unkeyed executions have
     * nothing to protect; a key already terminal (or reclaimed, or absent) is left alone.
     */
    private void protectKey(ExecutionRecord executionRecord) {
        String key = executionRecord.idempotencyKey();
        if (key == null || key.isBlank()) {
            return;
        }
        idempotencyStore.markTerminal(
                executionRecord.task().functionName(), key, executionRecord.executionId());
    }
}
