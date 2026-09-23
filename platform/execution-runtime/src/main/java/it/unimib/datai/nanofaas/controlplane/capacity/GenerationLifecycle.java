package it.unimib.datai.nanofaas.controlplane.capacity;

/**
 * The reusable active/retiring/closed state machine of one generation, together with
 * the count of resources that generation currently holds (ADR 0001 §8.2).
 *
 * <p>It answers the two questions every per-function resource owner asks — capacity
 * slots today, meters, replica snapshot entries and wake-up gates in the tasks that
 * follow: <em>may this generation still take new work?</em> and <em>has everything it
 * owned been given back?</em> Retirement only stops admission; the entry closes when
 * the last resource is released, so retired state never disappears while something it
 * owns is still in flight (invariant I7).
 *
 * <p>It deliberately holds <strong>no</strong> identity and <strong>no</strong> close
 * callback. The owner pairs it with its own {@link FunctionGeneration} and decides what
 * closing means: {@link #release()} and {@link #retire()} report the transition to
 * {@link GenerationPhase#CLOSED} as a return value, so the owner runs its own cleanup
 * outside its own locks. A callback fired from inside this class would run under
 * whatever monitor the caller holds and invert the lock order of every owner that
 * acquires it from within a wider critical section.
 *
 * <p>Thread-safe. Mutations are serialized on this instance; {@link #phase()} and
 * {@link #retained()} are non-blocking reads.
 */
public final class GenerationLifecycle {

    private volatile GenerationPhase phase = GenerationPhase.ACTIVE;
    private volatile int retained;

    public GenerationPhase phase() {
        return phase;
    }

    /** Whether the generation is still current, i.e. accepts new acquisitions. */
    public boolean isActive() {
        return phase.admitsNewWork();
    }

    /** How many resources this generation currently holds. */
    public int retained() {
        return retained;
    }

    /**
     * Acquires one resource when the generation is active and holds fewer than
     * {@code ceiling} — the ceiling check and the increment happen together, so the
     * owner's limit is never exceeded by a race between two acquisitions.
     *
     * @return {@code true} when a resource was retained
     */
    public synchronized boolean retainIfBelow(int ceiling) {
        if (!phase.admitsNewWork() || retained >= ceiling) {
            return false;
        }
        retained++;
        return true;
    }

    /**
     * Acquires one resource with no ceiling, refused once the generation is no longer
     * active.
     *
     * @return {@code true} when a resource was retained
     */
    public synchronized boolean retain() {
        if (!phase.admitsNewWork()) {
            return false;
        }
        retained++;
        return true;
    }

    /**
     * Gives one resource back. A release when nothing is retained is a no-op, so a
     * duplicate release can never take a resource the generation does not hold.
     *
     * @return {@code true} when this release closed the generation ({@code RETIRING ->
     *         CLOSED}) — reported to exactly one caller, so the owner's cleanup runs once
     */
    public synchronized boolean release() {
        if (retained == 0) {
            return false;
        }
        retained--;
        if (retained == 0 && phase == GenerationPhase.RETIRING) {
            phase = GenerationPhase.CLOSED;
            return true;
        }
        return false;
    }

    /**
     * Retires the generation: no further acquisition, whatever it holds keeps draining.
     * Idempotent — a second call reports no transition.
     *
     * @return {@code true} when this call closed the generation outright ({@code ACTIVE ->
     *         CLOSED}, nothing retained)
     */
    public synchronized boolean retire() {
        if (!phase.admitsNewWork()) {
            return false;
        }
        if (retained == 0) {
            phase = GenerationPhase.CLOSED;
            return true;
        }
        phase = GenerationPhase.RETIRING;
        return false;
    }
}
