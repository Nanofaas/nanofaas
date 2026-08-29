package it.unimib.datai.nanofaas.workloadmetrics;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

public final class FunctionCapacityState {
    private final LongSupplier nanoTime;
    private volatile int inFlight;
    private final Deque<Long> acquiredAt = new ArrayDeque<>();
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

    public synchronized boolean tryAcquireSlot() {
        if (inFlight >= effectiveConcurrency) return false;
        inFlight++;
        acquiredAt.addLast(nanoTime.getAsLong());
        return true;
    }

    public synchronized long releaseSlotAndGetHoldNanos() {
        if (inFlight == 0) return -1;
        inFlight--;
        Long started = acquiredAt.removeFirst();
        return started == null ? -1 : nanoTime.getAsLong() - started;
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
        effectiveConcurrency = Math.min(configuredConcurrency, Math.max(1, concurrency));
    }

    public int configuredConcurrency() { return configuredConcurrency; }
    public int effectiveConcurrency() { return effectiveConcurrency; }
    public int inFlight() { return inFlight; }
    public boolean canDispatch() { return inFlight < effectiveConcurrency; }
}
