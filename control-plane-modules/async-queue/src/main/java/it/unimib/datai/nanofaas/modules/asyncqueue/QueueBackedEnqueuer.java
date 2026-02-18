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
    public boolean tryAcquireSlot(String functionName) {
        return queueManager.tryAcquireSlot(functionName);
    }

    @Override
    public void releaseDispatchSlot(String functionName) {
        queueManager.releaseSlot(functionName);
    }
}
