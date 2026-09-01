package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import org.springframework.stereotype.Service;

/**
 * Single persistence point for a managed deployment's replica target: it durably commits the new
 * {@code desiredReplicas} to the {@link FunctionRegistry} before applying the change through the
 * backend provider, and serializes every caller (manual scaling, the autoscaler, and the deployment
 * wake-up gate) on the shared per-function lock.
 */
@Service
public class ManagedDeploymentCoordinator {

    private final DeploymentProviderResolver deploymentProviderResolver;
    private final FunctionRegistry registry;
    private final FunctionOperationLocks locks;

    public ManagedDeploymentCoordinator(DeploymentProviderResolver deploymentProviderResolver,
                                        FunctionRegistry registry,
                                        FunctionOperationLocks locks) {
        this.deploymentProviderResolver = deploymentProviderResolver;
        this.registry = registry;
        this.locks = locks;
    }

    public int getReadyReplicas(ManagedDeploymentTarget target) {
        return requireProvider(target).getReadyReplicas(target.functionName());
    }

    public ReplicaStatus getReplicaStatus(ManagedDeploymentTarget target) {
        return requireProvider(target).getReplicaStatus(target.functionName());
    }

    public void setReplicas(ManagedDeploymentTarget target, int replicas) {
        if (replicas < 0) {
            throw new IllegalArgumentException("replicas must be >= 0");
        }
        locks.withLock(target.functionName(), () -> {
            RegisteredFunction existing = registry.getRegistered(target.functionName())
                    .orElseThrow(() -> new IllegalStateException(
                            "Function '" + target.functionName() + "' is not registered"));
            if (existing.managedDeploymentTarget().filter(target::equals).isEmpty()) {
                throw new IllegalStateException("Persisted deployment backend does not match " + target);
            }

            RegisteredFunction updated = existing.withDesiredReplicas(replicas);
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
        });
    }

    public void deprovision(ManagedDeploymentTarget target) {
        requireProvider(target).deprovision(target.functionName());
    }

    public ManagedDeploymentProvider requireProvider(ManagedDeploymentTarget target) {
        return deploymentProviderResolver.requireBackend(target.backendId());
    }
}
