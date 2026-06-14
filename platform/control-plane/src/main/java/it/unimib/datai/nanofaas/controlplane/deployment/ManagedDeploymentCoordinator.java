package it.unimib.datai.nanofaas.controlplane.deployment;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;
import org.springframework.stereotype.Service;

@Service
public class ManagedDeploymentCoordinator {

    private final DeploymentProviderResolver deploymentProviderResolver;

    public ManagedDeploymentCoordinator(DeploymentProviderResolver deploymentProviderResolver) {
        this.deploymentProviderResolver = deploymentProviderResolver;
    }

    public boolean isManagedDeployment(RegisteredFunction function) {
        return function != null
                && function.deploymentMetadata().effectiveExecutionMode() == ExecutionMode.DEPLOYMENT
                && function.deploymentMetadata().deploymentBackend() != null
                && !function.deploymentMetadata().deploymentBackend().isBlank();
    }

    public int getReadyReplicas(RegisteredFunction function) {
        return requireProvider(function).getReadyReplicas(function.name());
    }

    public void setReplicas(RegisteredFunction function, int replicas) {
        requireProvider(function).setReplicas(function.name(), replicas);
    }

    public void deprovision(RegisteredFunction function) {
        requireProvider(function).deprovision(function.name());
    }

    public ManagedDeploymentProvider requireProvider(RegisteredFunction function) {
        if (!isManagedDeployment(function)) {
            throw new IllegalStateException(
                    "Function '" + (function == null ? null : function.name()) + "' is not a managed deployment");
        }
        return deploymentProviderResolver.requireBackend(function.deploymentMetadata().deploymentBackend());
    }
}
