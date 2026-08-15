package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;

import java.util.Optional;

public record RegisteredFunction(
        FunctionSpec spec,
        DeploymentMetadata deploymentMetadata
) {
    public RegisteredFunction {
        if (spec == null) {
            throw new IllegalArgumentException("spec is required");
        }
        deploymentMetadata = deploymentMetadata == null
                ? DeploymentMetadata.nonManaged(spec.executionMode(), spec.endpointUrl())
                : deploymentMetadata;
    }

    public static RegisteredFunction nonManaged(FunctionSpec spec) {
        return new RegisteredFunction(spec, DeploymentMetadata.nonManaged(spec.executionMode(), spec.endpointUrl()));
    }

    public String name() {
        return spec.name();
    }

    /**
     * The deployment this function is managed through, or empty when it is not managed by the
     * control plane (external endpoint, local execution, or a missing backend id).
     */
    public Optional<ManagedDeploymentTarget> managedDeploymentTarget() {
        if (deploymentMetadata.effectiveExecutionMode() != ExecutionMode.DEPLOYMENT) {
            return Optional.empty();
        }
        String backendId = deploymentMetadata.deploymentBackend();
        return backendId == null || backendId.isBlank()
                ? Optional.empty()
                : Optional.of(new ManagedDeploymentTarget(name(), backendId));
    }
}
