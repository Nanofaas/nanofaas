package it.unimib.datai.nanofaas.controlplane.sync;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

enum NoOpSyncQueueGateway implements SyncQueueGateway {
    INSTANCE;

    @Override
    public void enqueueOrThrow(InvocationTask task) {
        throw new UnsupportedOperationException("Sync queue module not loaded");
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public int retryAfterSeconds() {
        return 2;
    }
}
