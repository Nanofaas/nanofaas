package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

enum NoOpInvocationEnqueuer implements InvocationEnqueuer {
    INSTANCE;

    @Override
    public boolean enqueue(InvocationTask task) {
        throw new UnsupportedOperationException("Async queue module not loaded");
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public void decrementInFlight(String functionName) {
        // no-op
    }

    @Override
    public boolean tryAcquireSlot(String functionName) {
        return true;
    }

    @Override
    public void releaseSlot(String functionName) {
        // no-op
    }
}
