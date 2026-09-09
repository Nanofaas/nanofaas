package it.unimib.datai.nanofaas.controlplane.capacity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * The mutable per-generation capacity of one function incarnation.
 *
 * <p>This is the enforcement state the core applies limits against: it holds how
 * many slots are currently in flight, and the configured/effective concurrency
 * ceilings. The governor (an optional module) may regulate the effective value;
 * it does not own this state. Generation identity and retirement live in
 * {@link FunctionCapacityRegistry}, which keeps at most one active state per name
 * and lets retired states drain on their own.
 *
 * <p>Admission and drain follow the shared {@link GenerationLifecycle} protocol:
 * a slot is one retained resource, {@link #deactivate()} is the retirement
 * transition, and the state is drained only once its last slot comes back
 * ({@link GenerationPhase#CLOSED}).
 */
public final class FunctionCapacityState {
    private final LongSupplier nanoTime;
    private final Runnable onDrained;
    private final Deque<Long> acquiredAt = new ArrayDeque<>();
    private final GenerationLifecycle lifecycle = new GenerationLifecycle();
    private volatile int configuredConcurrency;
    private volatile int effectiveConcurrency;

    public FunctionCapacityState(int concurrency) {
        this(concurrency, System::nanoTime, null);
    }

    public FunctionCapacityState(int concurrency, LongSupplier nanoTime) {
        this(concurrency, nanoTime, null);
    }

    FunctionCapacityState(int concurrency, LongSupplier nanoTime, Runnable onDrained) {
        this.nanoTime = nanoTime;
        this.onDrained = onDrained;
        configuredConcurrency = Math.max(1, concurrency);
        effectiveConcurrency = configuredConcurrency;
    }

    public synchronized boolean tryAcquireSlot() {
        if (!lifecycle.retainIfBelow(effectiveConcurrency)) {
            return false;
        }
        acquiredAt.addLast(nanoTime.getAsLong());
        return true;
    }

    public synchronized void incrementInFlight() {
        if (lifecycle.retain()) {
            acquiredAt.addLast(nanoTime.getAsLong());
        }
    }

    public long releaseSlotAndGetHoldNanos() {
        long holdNanos;
        boolean drained;
        synchronized (this) {
            if (lifecycle.retained() == 0) {
                return -1;
            }
            drained = lifecycle.release();
            Long started = acquiredAt.pollFirst();
            holdNanos = started == null ? -1 : nanoTime.getAsLong() - started;
        }
        // Outside the monitor: the drain callback reaches back into the registry's lock.
        if (drained && onDrained != null) {
            onDrained.run();
        }
        return holdNanos;
    }

    public void releaseSlot() {
        releaseSlotAndGetHoldNanos();
    }

    public synchronized void concurrency(int concurrency) {
        int previous = configuredConcurrency;
        int normalized = Math.max(1, concurrency);
        configuredConcurrency = normalized;
        if (effectiveConcurrency == previous || effectiveConcurrency > normalized) {
            effectiveConcurrency = normalized;
        }
    }

    public synchronized void setEffectiveConcurrency(int concurrency) {
        effectiveConcurrency = Math.clamp(concurrency, 1, configuredConcurrency);
    }

    public int configuredConcurrency() {
        return configuredConcurrency;
    }

    public int effectiveConcurrency() {
        return effectiveConcurrency;
    }

    public int inFlight() {
        return lifecycle.retained();
    }

    public boolean canDispatch() {
        return lifecycle.isActive() && lifecycle.retained() < effectiveConcurrency;
    }

    public boolean isActive() {
        return lifecycle.isActive();
    }

    /** Where this generation stands in the active/retiring/closed protocol. */
    public GenerationPhase phase() {
        return lifecycle.phase();
    }

    /**
     * Retires this generation: no further acquisition, in-flight work drains on release.
     * Idempotent — the drain callback runs once, on the transition that actually closes
     * the generation.
     */
    public void deactivate() {
        boolean drained;
        synchronized (this) {
            drained = lifecycle.retire();
        }
        // Outside the monitor: the drain callback reaches back into the registry's lock.
        if (drained && onDrained != null) {
            onDrained.run();
        }
    }
}
