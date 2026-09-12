package it.unimib.datai.nanofaas.controlplane.deployment;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

import java.util.concurrent.CompletableFuture;

/**
 * Readiness when no managed deployment provider is configured: a LOCAL or EXTERNAL backend is
 * always as ready as it will ever be, so there is nothing to wake and nothing to wait for.
 */
enum ImmediateDeploymentReadiness implements DeploymentReadiness {
    INSTANCE;

    @Override
    public CompletableFuture<Void> ensureReady(InvocationTask task) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isImmediate() {
        return true;
    }
}
