package it.unimib.datai.nanofaas.workloadmetrics;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

public final class FunctionCapacityState {
    private final LongSupplier nanoTime;
    private final Runnable onDrained;
    private volatile int inFlight;
    private final Deque<Long> acquiredAt = new ArrayDeque<>();
    private volatile int configuredConcurrency;
    private volatile int effectiveConcurrency;
    private volatile boolean active = true;

    public FunctionCapacityState(int concurrency) { this(concurrency, System::nanoTime, null); }

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
        if (!active || inFlight >= effectiveConcurrency) return false;
        inFlight++;
        acquiredAt.addLast(nanoTime.getAsLong());
        return true;
    }

    public synchronized void incrementInFlight() {
        if (!active) return;
        inFlight++;
        acquiredAt.addLast(nanoTime.getAsLong());
    }

    public long releaseSlotAndGetHoldNanos() {
        long holdNanos;
        boolean drained;
        synchronized (this) {
            if (inFlight == 0) return -1;
            inFlight--;
            Long started = acquiredAt.removeFirst();
            holdNanos = started == null ? -1 : nanoTime.getAsLong() - started;
            drained = !active && inFlight == 0;
        }
        if (drained && onDrained != null) onDrained.run();
        return holdNanos;
    }

    public void releaseSlot() { releaseSlotAndGetHoldNanos(); }

    public synchronized void concurrency(int concurrency) {
        int previous = configuredConcurrency;
        int normalized = Math.max(1, concurrency);
        configuredConcurrency = normalized;
        if (effectiveConcurrency == previous || effectiveConcurrency > normalized) {
            effectiveConcurrency = normalized;
        }
    }

    /**
     * Put a retired state back in service for a re-registration that arrives while it still
     * drains. Both limits are reset: {@link #concurrency(int)} deliberately preserves a lower
     * adaptive limit, but that limit belonged to the removed registration.
     */
    synchronized void reactivate(int concurrency) {
        int normalized = Math.max(1, concurrency);
        configuredConcurrency = normalized;
        effectiveConcurrency = normalized;
        active = true;
    }

    public synchronized void setEffectiveConcurrency(int concurrency) {
        effectiveConcurrency = Math.min(configuredConcurrency, Math.max(1, concurrency));
    }

    public int configuredConcurrency() { return configuredConcurrency; }
    public int effectiveConcurrency() { return effectiveConcurrency; }
    public int inFlight() { return inFlight; }
    public boolean canDispatch() { return active && inFlight < effectiveConcurrency; }
    public boolean isActive() { return active; }
    public void deactivate() {
        boolean drained;
        synchronized (this) {
            active = false;
            drained = inFlight == 0;
        }
        if (drained && onDrained != null) onDrained.run();
    }
}
