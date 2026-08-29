package it.unimib.datai.nanofaas.workloadmetrics;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

final class FunctionCapacityState {
    private final LongSupplier nanoTime;
    private volatile int inFlight;
    private final Deque<Long> acquiredAt = new ArrayDeque<>();
    private volatile int configuredConcurrency;
    private volatile int effectiveConcurrency;
    private volatile boolean active = true;

    FunctionCapacityState(int concurrency) {
        this(concurrency, System::nanoTime);
    }

    FunctionCapacityState(int concurrency, LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        configuredConcurrency = Math.max(1, concurrency);
        effectiveConcurrency = configuredConcurrency;
    }

    synchronized boolean tryAcquireSlot() {
        if (!active || inFlight >= effectiveConcurrency) return false;
        inFlight++;
        acquiredAt.addLast(nanoTime.getAsLong());
        return true;
    }

    synchronized long releaseSlotAndGetHoldNanos() {
        if (inFlight == 0) return -1;
        inFlight--;
        Long started = acquiredAt.removeFirst();
        return started == null ? -1 : nanoTime.getAsLong() - started;
    }

    void releaseSlot() {
        releaseSlotAndGetHoldNanos();
    }

    synchronized void concurrency(int concurrency) {
        int previous = configuredConcurrency;
        int normalized = Math.max(1, concurrency);
        configuredConcurrency = normalized;
        if (effectiveConcurrency == previous || effectiveConcurrency > normalized) {
            effectiveConcurrency = normalized;
        }
    }

    synchronized void setEffectiveConcurrency(int concurrency) {
        effectiveConcurrency = Math.min(configuredConcurrency, Math.max(1, concurrency));
    }

    int configuredConcurrency() { return configuredConcurrency; }
    int effectiveConcurrency() { return effectiveConcurrency; }
    int inFlight() { return inFlight; }
    boolean canDispatch() { return active && inFlight < effectiveConcurrency; }
    void deactivate() { active = false; }
}
