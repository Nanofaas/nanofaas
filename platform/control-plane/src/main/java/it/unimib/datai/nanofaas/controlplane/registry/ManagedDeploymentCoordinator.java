package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.InstantSource;

/**
 * Single persistence point for a managed deployment's replica target: it durably commits the new
 * {@code desiredReplicas} to the {@link FunctionRegistry} before applying the change through the
 * backend provider, and serializes every caller (manual scaling, the autoscaler, and the deployment
 * wake-up gate) on the shared per-function lock.
 *
 * <p>Replica-status reads go through a {@link ReplicaStatusSnapshot} shared by every consumer, so
 * the autoscaler, the governor and any other periodic reader hit the provider at most once per TTL
 * window. Wake-up and lifecycle paths force a fresh read through {@link #getFreshReplicaStatus}.</p>
 */
@Service
public class ManagedDeploymentCoordinator {

    private final DeploymentProviderResolver deploymentProviderResolver;
    private final FunctionRegistry registry;
    private final FunctionOperationLocks locks;
    private final ReplicaStatusSnapshot snapshot;

    @Autowired
    public ManagedDeploymentCoordinator(DeploymentProviderResolver deploymentProviderResolver,
                                        FunctionRegistry registry,
                                        FunctionOperationLocks locks) {
        this(deploymentProviderResolver, registry, locks,
                ReplicaStatusSnapshot.withDefaults(InstantSource.system()));
    }

    // Package-private for tests: inject a snapshot with a steerable clock or executor.
    ManagedDeploymentCoordinator(DeploymentProviderResolver deploymentProviderResolver,
                                 FunctionRegistry registry,
                                 FunctionOperationLocks locks,
                                 ReplicaStatusSnapshot snapshot) {
        this.deploymentProviderResolver = deploymentProviderResolver;
        this.registry = registry;
        this.locks = locks;
        this.snapshot = snapshot;
    }

    public int getReadyReplicas(ManagedDeploymentTarget target) {
        return getReplicaStatus(target).readyReplicas();
    }

    /** Cached read shared by the periodic consumers (autoscaler, concurrency governor, ...). */
    public ReplicaStatus getReplicaStatus(ManagedDeploymentTarget target) {
        return snapshot.read(target, this::fetchReplicaStatus);
    }

    /** Forced fresh read for wake-up and lifecycle paths (still single-flight). */
    public ReplicaStatus getFreshReplicaStatus(ManagedDeploymentTarget target) {
        return snapshot.refresh(target, this::fetchReplicaStatus);
    }

    /**
     * Drops the cached replica status for a function after a target change, removal or
     * re-registration, so the next read re-fetches instead of serving stale data.
     */
    public void invalidate(ManagedDeploymentTarget target) {
        snapshot.invalidate(target.functionName());
    }

    private ReplicaStatus fetchReplicaStatus(ManagedDeploymentTarget target) {
        return requireProvider(target).getReplicaStatus(target.functionName());
    }

    /**
     * Persists the new replica target and applies it through the backend provider.
     *
     * @return {@code false} when the function is no longer registered (e.g. a concurrent removal
     *         won the race); {@code true} once the change has been applied.
     */
    public boolean setReplicas(ManagedDeploymentTarget target, int replicas) {
        if (replicas < 0) {
            throw new IllegalArgumentException("replicas must be >= 0");
        }
        return locks.withLock(target.functionName(), () -> {
            RegisteredFunction existing = registry.getRegistered(target.functionName()).orElse(null);
            if (existing == null) {
                return false;
            }
            if (existing.managedDeploymentTarget().filter(target::equals).isEmpty()) {
                // A stale target (removed and re-registered under a different backend between the
                // caller's lookup and this locked re-check) is a no-op, not an error.
                return false;
            }

            RegisteredFunction updated = existing.withDesiredReplicas(replicas);
            // ponytail: durable-first. Persisting before applying keeps the target across a crash
            // after a successful scale, but a provider failure whose rollback save also fails leaves
            // a never-applied target that the next restart's reconcile enforces. A full fix needs an
            // intent journal, out of scope for the MVP.
            registry.put(updated);
            try {
                requireProvider(target).setReplicas(target.functionName(), replicas);
            } catch (RuntimeException failure) {
                try {
                    registry.put(existing);
                } catch (RuntimeException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
            // The target changed: forget the cached read so the next one re-fetches the new count.
            snapshot.invalidate(target.functionName());
            return true;
        });
    }

    public void deprovision(ManagedDeploymentTarget target) {
        try {
            requireProvider(target).deprovision(target.functionName());
        } finally {
            // Even a deprovision that only partly succeeded changed what the backend holds: a
            // status cached from before it would be read as truth by the next observer.
            snapshot.invalidate(target.functionName());
        }
    }

    public ManagedDeploymentProvider requireProvider(ManagedDeploymentTarget target) {
        return deploymentProviderResolver.requireBackend(target.backendId());
    }
}
