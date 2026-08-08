package it.unimib.datai.nanofaas.controlplane.deployment;

import org.springframework.stereotype.Service;

@Service
public class ManagedDeploymentCoordinator {

    private final DeploymentProviderResolver deploymentProviderResolver;

    public ManagedDeploymentCoordinator(DeploymentProviderResolver deploymentProviderResolver) {
        this.deploymentProviderResolver = deploymentProviderResolver;
    }

    public int getReadyReplicas(ManagedDeploymentTarget target) {
        return requireProvider(target).getReadyReplicas(target.functionName());
    }

    public ReplicaStatus getReplicaStatus(ManagedDeploymentTarget target) {
        return requireProvider(target).getReplicaStatus(target.functionName());
    }

    public void setReplicas(ManagedDeploymentTarget target, int replicas) {
        requireProvider(target).setReplicas(target.functionName(), replicas);
    }

    public void deprovision(ManagedDeploymentTarget target) {
        requireProvider(target).deprovision(target.functionName());
    }

    public ManagedDeploymentProvider requireProvider(ManagedDeploymentTarget target) {
        return deploymentProviderResolver.requireBackend(target.backendId());
    }
}
