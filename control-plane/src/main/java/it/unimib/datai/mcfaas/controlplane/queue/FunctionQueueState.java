package it.unimib.datai.mcfaas.controlplane.queue;

import it.unimib.datai.mcfaas.controlplane.scheduler.InvocationTask;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class FunctionQueueState {
    private final String functionName;
    private final ArrayBlockingQueue<InvocationTask> queue;
    private final AtomicInteger inFlight;
    private volatile int concurrency;

    public FunctionQueueState(String functionName, int queueSize, int concurrency) {
        this.functionName = functionName;
        this.queue = new ArrayBlockingQueue<>(queueSize);
        this.inFlight = new AtomicInteger();
        this.concurrency = concurrency;
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
            if (current >= concurrency) {
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
        inFlight.decrementAndGet();
    }

    /**
     * @deprecated Use {@link #tryAcquireSlot()} for thread-safe dispatch slot acquisition
     */
    @Deprecated
    public boolean canDispatch() {
        return inFlight.get() < concurrency;
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
        inFlight.decrementAndGet();
    }

    public void concurrency(int concurrency) {
        this.concurrency = concurrency;
    }
}
