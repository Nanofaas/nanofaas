package it.unimib.datai.nanofaas.controlplane.deployment;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

import java.util.concurrent.CompletableFuture;

/**
 * Whether a function's backend is ready to receive an attempt, and how to make it so.
 *
 * <p>The dispatch path asks this before every DEPLOYMENT attempt. With a managed provider the
 * answer can take time — a function scaled to zero has to be woken first — so the answer is a
 * future rather than a boolean: a caller that only learned "not ready" would have nothing to wait
 * on and would have to poll.</p>
 *
 * <p>With no managed provider there is nothing to wake, and {@link #immediate()} answers so
 * without allocating. The dispatch path asks {@link #isImmediate()} to keep the LOCAL/EXTERNAL
 * hot path exactly as short as it was before this port existed: no wrapper, no extra future.</p>
 */
public interface DeploymentReadiness {

    /**
     * Completes once {@code task}'s backend can take the attempt, or completes exceptionally when
     * readiness could not be established within the configured wake-up budget.
     */
    CompletableFuture<Void> ensureReady(InvocationTask task);

    /**
     * {@code true} when readiness is never in question, so the caller may dispatch directly.
     * Implementations that can ever wait must leave this {@code false}.
     */
    default boolean isImmediate() {
        return false;
    }

    /** Readiness for a control plane with no managed deployment: nothing to wake, ever. */
    static DeploymentReadiness immediate() {
        return ImmediateDeploymentReadiness.INSTANCE;
    }
}
