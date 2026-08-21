package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class FunctionQueueState {
    private final String functionName;
    private final ArrayBlockingQueue<InvocationTask> queue;
    // ponytail: FIFO preserves the aggregate mean; correlate by invocation only for per-request percentiles.
    private final ConcurrentLinkedQueue<Long> slotAcquiredAtNanos = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlight;
    private volatile int configuredConcurrency;
    private volatile int effectiveConcurrency;
    private boolean closed;

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

    public int dispatchableBacklog() {
        return canDispatch() ? queued() : 0;
    }

    public synchronized boolean offer(InvocationTask task) {
        if (closed) {
            return false;
        }
        return queue.offer(task);
    }

    public synchronized InvocationTask poll() {
        return queue.poll();
    }

    public synchronized List<InvocationTask> closeAndDrainQueued() {
        closed = true;
        List<InvocationTask> drained = new ArrayList<>();
        queue.drainTo(drained);
        return drained;
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
                slotAcquiredAtNanos.add(System.nanoTime());
                return true;
            }
            // CAS failed, another thread modified - retry
        }
    }

    /**
     * Releases a dispatch slot. Must be called after dispatch completes.
     */
    public void releaseSlot() {
        releaseSlotAndGetHoldNanos();
    }

    long releaseSlotAndGetHoldNanos() {
        if (!decrementInFlightNonNegative()) {
            return -1;
        }
        Long acquiredAt = slotAcquiredAtNanos.poll();
        return acquiredAt == null ? -1 : System.nanoTime() - acquiredAt;
    }

    public boolean canDispatch() {
        return inFlight.get() < effectiveConcurrency;
    }

    public void incrementInFlight() {
        inFlight.incrementAndGet();
        slotAcquiredAtNanos.add(System.nanoTime());
    }

    public void decrementInFlight() {
        releaseSlotAndGetHoldNanos();
    }

    public void concurrency(int concurrency) {
        int previousConfigured = this.configuredConcurrency;
        int normalized = Math.max(1, concurrency);
        this.configuredConcurrency = normalized;
        if (effectiveConcurrency == previousConfigured || effectiveConcurrency > normalized) {
            // Preserve fixed-mode semantics: effective limit tracks configured limit (and clamps down on shrink).
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

    private boolean decrementInFlightNonNegative() {
        while (true) {
            int current = inFlight.get();
            if (current == 0) {
                return false;
            }
            if (inFlight.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }
}
