package it.unimib.datai.nanofaas.controlplane.capacity;

/**
 * The three phases one {@linkplain FunctionGeneration generation} of a
 * per-function resource owner goes through (ADR 0001 §8.2).
 *
 * <p>Transitions, all of them one-way:
 *
 * <ul>
 *   <li>{@code ACTIVE -> RETIRING} — the incarnation was removed or replaced while it
 *       still holds resources. It admits no new work; what it already holds keeps
 *       draining under the old identity.</li>
 *   <li>{@code ACTIVE -> CLOSED} — removed or replaced while holding nothing: there is
 *       nothing to drain, so it closes at once.</li>
 *   <li>{@code RETIRING -> CLOSED} — the last resource the generation owned was
 *       released.</li>
 * </ul>
 *
 * <p>{@code CLOSED} is terminal: an owner may drop its entry only here, which is why
 * retired state disappears after — never before — the resources it owns are released
 * (invariant I7). A stale event that arrives for a {@code RETIRING} or {@code CLOSED}
 * generation may release or close that generation's own resources; it may never
 * re-create the entry or touch the generation that replaced it.
 */
public enum GenerationPhase {
    /** Registered and current: the only phase that admits new work. */
    ACTIVE,
    /** Removed or replaced, still draining resources it acquired while active. */
    RETIRING,
    /** Drained: it owns nothing, and its owner may drop it. Terminal. */
    CLOSED;

    /** Whether a new resource may be acquired under this generation. */
    public boolean admitsNewWork() {
        return this == ACTIVE;
    }

    public boolean isClosed() {
        return this == CLOSED;
    }
}
