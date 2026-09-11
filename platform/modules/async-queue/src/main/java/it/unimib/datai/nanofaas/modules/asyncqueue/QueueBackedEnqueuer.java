package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.controlplane.service.RetryScheduler;

public class QueueBackedEnqueuer implements InvocationEnqueuer, RetryScheduler {
    private final QueueManager queueManager;

    public QueueBackedEnqueuer(QueueManager queueManager) {
        this.queueManager = queueManager;
    }

    @Override public QueueStrategy queueStrategy() { return QueueStrategy.FUNCTION_QUEUE; }

    @Override
    public boolean enqueue(InvocationTask task) {
        return queueManager.enqueue(task);
    }

    @Override
    public boolean supportsAsync() {
        return true;
    }

    @Override
    public boolean isQueueFull(String functionName) {
        return queueManager.isQueueFull(functionName);
    }

}
