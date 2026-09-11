package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

enum NoOpInvocationEnqueuer implements InvocationEnqueuer {
    INSTANCE;
    @Override public QueueStrategy queueStrategy() { return QueueStrategy.DIRECT; }
    @Override public boolean supportsAsync() { return false; }
    @Override public boolean enqueue(InvocationTask task) {
        throw new UnsupportedOperationException("Async queue module not loaded");
    }
}
