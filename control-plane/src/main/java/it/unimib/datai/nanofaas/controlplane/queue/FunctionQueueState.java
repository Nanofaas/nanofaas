package it.unimib.datai.nanofaas.controlplane.queue;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class FunctionQueueState {
    private final String functionName;
    private final ArrayBlockingQueue<InvocationTask> queue;
    private final AtomicInteger inFlight;
    private volatile int configuredConcurrency;
    private volatile int effectiveConcurrency;

    public FunctionQueueState(String functionName, int queueSize, int concurrency) {
        this.functionName = functionName;
        this.queue = new ArrayBlockingQueue<>(queueSize);
        this.inFlight = new AtomicInteger();
        this.configuredConcurrency = Math.max(1, concurrency);
        this.effectiveConcurrency = Math.max(1, concurrency);
    }

    public String functionName() {
        return functionName;
    }

    public int queueSize() {
        return queue.remainingCapacity() + queue.size();
    }

    public int queued() {
        return queue.size();
    }

    public boolean offer(InvocationTask task) {
        return queue.offer(task);
    }

    public InvocationTask poll() {
        return queue.poll();
    }

    public int inFlight() {
        return inFlight.get();
    }

    /**
     * Atomically checks if dispatch is allowed and increments inFlight if so.
     * This prevents race conditions where multiple threads could both pass
     * canDispatch() check and then both increment, exceeding the concurrency limit.
     *
     * @return true if a dispatch slot was acquired, false if limit reached
     */
    public boolean tryAcquireSlot() {
        while (true) {
            int current = inFlight.get();
            if (current >= effectiveConcurrency) {
                return false;
            }
            if (inFlight.compareAndSet(current, current + 1)) {
                return true;
            }
            // CAS failed, another thread modified - retry
        }
    }

    /**
     * Releases a dispatch slot. Must be called after dispatch completes.
     */
    public void releaseSlot() {
        decrementInFlightNonNegative();
    }

    /**
     * @deprecated Use {@link #tryAcquireSlot()} for thread-safe dispatch slot acquisition
     */
    @Deprecated
    public boolean canDispatch() {
        return inFlight.get() < effectiveConcurrency;
    }

    /**
     * @deprecated Use {@link #tryAcquireSlot()} for thread-safe dispatch slot acquisition
     */
    @Deprecated
    public void incrementInFlight() {
        inFlight.incrementAndGet();
    }

    /**
     * @deprecated Use {@link #releaseSlot()} instead
     */
    @Deprecated
    public void decrementInFlight() {
        decrementInFlightNonNegative();
    }

    public void concurrency(int concurrency) {
        int previousConfigured = this.configuredConcurrency;
        int normalized = Math.max(1, concurrency);
        this.configuredConcurrency = normalized;
        if (effectiveConcurrency == previousConfigured) {
            // Preserve fixed-mode semantics: effective limit tracks configured limit.
            effectiveConcurrency = normalized;
        } else if (effectiveConcurrency > normalized) {
            effectiveConcurrency = normalized;
        }
    }

    public int configuredConcurrency() {
        return configuredConcurrency;
    }

    public int effectiveConcurrency() {
        return effectiveConcurrency;
    }

    public void setEffectiveConcurrency(int effectiveConcurrency) {
        int clamped = Math.max(1, effectiveConcurrency);
        if (clamped > configuredConcurrency) {
            clamped = configuredConcurrency;
        }
        this.effectiveConcurrency = clamped;
    }

    private void decrementInFlightNonNegative() {
        while (true) {
            int current = inFlight.get();
            if (current == 0) {
                return;
            }
            if (inFlight.compareAndSet(current, current - 1)) {
                return;
            }
        }
    }
}
