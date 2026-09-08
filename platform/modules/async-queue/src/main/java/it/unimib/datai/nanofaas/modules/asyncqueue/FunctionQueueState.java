package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class FunctionQueueState {
    private final String functionName;
    private final ArrayBlockingQueue<InvocationTask> queue;
    private final FunctionCapacityState capacity;
    private final EnumMap<InvocationKind, AtomicInteger> queuedByKind = new EnumMap<>(InvocationKind.class);
    private boolean closed;

    public FunctionQueueState(String functionName, int queueSize, int concurrency) {
        this(functionName, queueSize, new FunctionCapacityState(concurrency));
    }

    FunctionQueueState(String functionName, int queueSize, int concurrency, java.util.function.LongSupplier nanoTime) {
        this(functionName, queueSize, new FunctionCapacityState(concurrency, nanoTime));
    }

    FunctionQueueState(String functionName, int queueSize, FunctionCapacityState capacity) {
        this.functionName = functionName;
        this.queue = new ArrayBlockingQueue<>(queueSize);
        this.capacity = capacity;
        for (InvocationKind kind : InvocationKind.values()) queuedByKind.put(kind, new AtomicInteger());
    }

    public String functionName() { return functionName; }
    public int queueSize() { return queue.remainingCapacity() + queue.size(); }
    public boolean hasQueueCapacity() { return queue.remainingCapacity() > 0; }
    public int queued() { return queue.size(); }
    public int dispatchableBacklog() { return canDispatch() ? queued() : 0; }

    public synchronized boolean offer(InvocationTask task) {
        if (closed) return false;
        boolean accepted = queue.offer(task);
        if (accepted) queuedByKind.get(task.kind()).incrementAndGet();
        return accepted;
    }

    public synchronized InvocationTask poll() {
        InvocationTask task = queue.poll();
        if (task != null) queuedByKind.get(task.kind()).decrementAndGet();
        return task;
    }

    public int queued(InvocationKind kind) { return queuedByKind.get(kind).get(); }

    public synchronized List<InvocationTask> closeAndDrainQueued() {
        closed = true;
        List<InvocationTask> drained = new ArrayList<>();
        queue.drainTo(drained);
        drained.forEach(task -> queuedByKind.get(task.kind()).decrementAndGet());
        return drained;
    }

    public int inFlight() { return capacity.inFlight(); }
    public boolean tryAcquireSlot() { return capacity.tryAcquireSlot(); }
    public void releaseSlot() { capacity.releaseSlot(); }
    long releaseSlotAndGetHoldNanos() { return capacity.releaseSlotAndGetHoldNanos(); }
    public boolean canDispatch() { return capacity.canDispatch(); }
    public void incrementInFlight() { capacity.incrementInFlight(); }
    public void decrementInFlight() { capacity.releaseSlot(); }
    public void concurrency(int concurrency) { capacity.concurrency(concurrency); }
    public int configuredConcurrency() { return capacity.configuredConcurrency(); }
    public int effectiveConcurrency() { return capacity.effectiveConcurrency(); }
    public void setEffectiveConcurrency(int concurrency) { capacity.setEffectiveConcurrency(concurrency); }
}
