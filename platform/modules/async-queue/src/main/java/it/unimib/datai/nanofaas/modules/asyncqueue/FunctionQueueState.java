package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.capacity.CapacityView;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class FunctionQueueState {
    private final String functionName;
    private final ArrayBlockingQueue<InvocationTask> queue;
    private final int queueSize;
    private final Map<String, InvocationTask> dispatchReservations = new HashMap<>();
    private final CapacityView capacity;
    private final EnumMap<InvocationKind, AtomicInteger> queuedByKind = new EnumMap<>(InvocationKind.class);
    private boolean closed;

    FunctionQueueState(String functionName, int queueSize, CapacityView capacity) {
        this.functionName = functionName;
        this.queueSize = queueSize;
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
        if (closed || queue.size() + dispatchReservations.size() >= queueSize) return false;
        boolean accepted = queue.offer(task);
        if (accepted) queuedByKind.get(task.kind()).incrementAndGet();
        return accepted;
    }

    public synchronized InvocationTask poll() {
        return poll(false);
    }

    /** Dequeues while retaining the bounded queue slot until dispatch commits or requeues. */
    public synchronized InvocationTask pollForDispatch() {
        return poll(true);
    }

    private InvocationTask poll(boolean reserveDispatchSlot) {
        InvocationTask task = queue.poll();
        if (task != null) {
            queuedByKind.get(task.kind()).decrementAndGet();
            if (reserveDispatchSlot) dispatchReservations.put(dispatchKey(task), task);
        }
        return task;
    }

    public synchronized boolean requeueAfterInputBackpressure(InvocationTask task) {
        InvocationTask reserved = dispatchReservations.remove(dispatchKey(task));
        if (reserved == null && !closed) {
            throw new IllegalStateException("queue task has no dispatch reservation");
        }
        if (closed) return false;
        boolean accepted = queue.offer(task);
        if (!accepted) {
            throw new IllegalStateException("reserved queue slot was unavailable");
        }
        queuedByKind.get(task.kind()).incrementAndGet();
        return true;
    }

    public synchronized void completeDispatchReservation(InvocationTask task) {
        dispatchReservations.remove(dispatchKey(task));
    }

    private static String dispatchKey(InvocationTask task) {
        return task.executionId() + "/" + task.attempt();
    }

    public int queued(InvocationKind kind) { return queuedByKind.get(kind).get(); }

    public synchronized List<InvocationTask> closeAndDrainQueued() {
        closed = true;
        List<InvocationTask> drained = new ArrayList<>();
        queue.drainTo(drained);
        drained.forEach(task -> queuedByKind.get(task.kind()).decrementAndGet());
        drained.addAll(dispatchReservations.values());
        dispatchReservations.clear();
        return drained;
    }

    CapacityView capacity() { return capacity; }

    public int inFlight() { return capacity.inFlight(); }
    public boolean canDispatch() { return capacity.canDispatch(); }
    public int configuredConcurrency() { return capacity.configuredConcurrency(); }
    public int effectiveConcurrency() { return capacity.effectiveConcurrency(); }
}
