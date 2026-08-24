package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

public class FunctionQueueState {
    private final String functionName;
    private final ArrayBlockingQueue<InvocationTask> queue;
    private final LongSupplier nanoTime;
    // ponytail: FIFO preserves the aggregate mean; correlate by invocation only for per-request percentiles.
    private final ConcurrentLinkedQueue<Long> slotAcquiredAtNanos = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlight;
    // Two counters, not a second queue: the order stays one FIFO, but the backlog can
    // now say how much of itself is work nobody is waiting for.
    private final EnumMap<InvocationKind, AtomicInteger> queuedByKind = new EnumMap<>(InvocationKind.class);
    private volatile int configuredConcurrency;
    private volatile int effectiveConcurrency;
    private boolean closed;

    public FunctionQueueState(String functionName, int queueSize, int concurrency) {
        this(functionName, queueSize, concurrency, System::nanoTime);
    }

    FunctionQueueState(String functionName, int queueSize, int concurrency, LongSupplier nanoTime) {
        this.functionName = functionName;
        this.queue = new ArrayBlockingQueue<>(queueSize);
        this.nanoTime = nanoTime;
        this.inFlight = new AtomicInteger();
        for (InvocationKind kind : InvocationKind.values()) {
            queuedByKind.put(kind, new AtomicInteger());
        }
        this.configuredConcurrency = Math.max(1, concurrency);
        this.effectiveConcurrency = Math.max(1, concurrency);
    }

    public String functionName() {
        return functionName;
    }

    public int queueSize() {
        return queue.remainingCapacity() + queue.size();
    }

    /**
     * A hint, deliberately unsynchronized: `offer` remains the authority. Callers use
     * it to skip work that a full queue would throw away, and a slot freed between the
     * two only costs one refusal that the caller was about to receive anyway.
     */
    public boolean hasQueueCapacity() {
        return queue.remainingCapacity() > 0;
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
        boolean accepted = queue.offer(task);
        if (accepted) {
            queuedByKind.get(task.kind()).incrementAndGet();
        }
        return accepted;
    }

    public synchronized InvocationTask poll() {
        InvocationTask task = queue.poll();
        if (task != null) {
            queuedByKind.get(task.kind()).decrementAndGet();
        }
        return task;
    }

    public int queued(InvocationKind kind) {
        return queuedByKind.get(kind).get();
    }

    public synchronized List<InvocationTask> closeAndDrainQueued() {
        closed = true;
        List<InvocationTask> drained = new ArrayList<>();
        queue.drainTo(drained);
        drained.forEach(task -> queuedByKind.get(task.kind()).decrementAndGet());
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
                slotAcquiredAtNanos.add(nanoTime.getAsLong());
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
        return acquiredAt == null ? -1 : nanoTime.getAsLong() - acquiredAt;
    }

    public boolean canDispatch() {
        return inFlight.get() < effectiveConcurrency;
    }

    public void incrementInFlight() {
        inFlight.incrementAndGet();
        slotAcquiredAtNanos.add(nanoTime.getAsLong());
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
