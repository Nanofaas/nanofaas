package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;

public class QueueBackedEnqueuer implements InvocationEnqueuer {
    private final QueueManager queueManager;

    public QueueBackedEnqueuer(QueueManager queueManager) {
        this.queueManager = queueManager;
    }

    @Override
    public boolean enqueue(InvocationTask task) {
        return queueManager.enqueue(task);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void decrementInFlight(String functionName) {
        // Slot release is handled by releaseSlot(functionName).
        // Keeping this as a no-op avoids double-decrement when callers invoke both APIs.
    }

    @Override
    public boolean tryAcquireSlot(String functionName) {
        return queueManager.tryAcquireSlot(functionName);
    }

    @Override
    public void releaseSlot(String functionName) {
        queueManager.releaseSlot(functionName);
    }
}
