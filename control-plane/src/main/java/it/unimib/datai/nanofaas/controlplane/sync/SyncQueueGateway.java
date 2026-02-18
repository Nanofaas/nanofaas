package it.unimib.datai.nanofaas.controlplane.sync;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

/**
 * Core abstraction for optional sync-queue module integration.
 */
public interface SyncQueueGateway {

    void enqueueOrThrow(InvocationTask task);

    boolean enabled();

    int retryAfterSeconds();

    static SyncQueueGateway noOp() {
        return NoOpSyncQueueGateway.INSTANCE;
    }
}
