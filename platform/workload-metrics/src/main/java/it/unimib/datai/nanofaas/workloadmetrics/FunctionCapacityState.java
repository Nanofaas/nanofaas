package it.unimib.datai.nanofaas.workloadmetrics;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

public final class FunctionCapacityState {
    private final LongSupplier nanoTime;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ConcurrentLinkedQueue<Long> acquiredAt = new ConcurrentLinkedQueue<>();
    private volatile int configuredConcurrency;
    private volatile int effectiveConcurrency;

    public FunctionCapacityState(int concurrency) {
        this(concurrency, System::nanoTime);
    }

    FunctionCapacityState(int concurrency, LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        configuredConcurrency = Math.max(1, concurrency);
        effectiveConcurrency = configuredConcurrency;
    }

    public boolean tryAcquireSlot() {
        while (true) {
            int current = inFlight.get();
            if (current >= effectiveConcurrency) return false;
            if (inFlight.compareAndSet(current, current + 1)) {
                acquiredAt.add(nanoTime.getAsLong());
                return true;
            }
        }
    }

    public long releaseSlotAndGetHoldNanos() {
        if (!decrementInFlight()) return -1;
        Long started = acquiredAt.poll();
        return started == null ? -1 : nanoTime.getAsLong() - started;
    }

    public void releaseSlot() {
        releaseSlotAndGetHoldNanos();
    }

    public void concurrency(int concurrency) {
        int previous = configuredConcurrency;
        int normalized = Math.max(1, concurrency);
        configuredConcurrency = normalized;
        if (effectiveConcurrency == previous || effectiveConcurrency > normalized) {
            effectiveConcurrency = normalized;
        }
    }

    public void setEffectiveConcurrency(int concurrency) {
        effectiveConcurrency = Math.min(configuredConcurrency, Math.max(1, concurrency));
    }

    public int configuredConcurrency() { return configuredConcurrency; }
    public int effectiveConcurrency() { return effectiveConcurrency; }
    public int inFlight() { return inFlight.get(); }
    public boolean canDispatch() { return inFlight.get() < effectiveConcurrency; }

    private boolean decrementInFlight() {
        while (true) {
            int current = inFlight.get();
            if (current == 0) return false;
            if (inFlight.compareAndSet(current, current - 1)) return true;
        }
    }
}
