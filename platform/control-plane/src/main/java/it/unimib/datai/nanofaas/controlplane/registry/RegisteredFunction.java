package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;

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
        if (deploymentMetadata.effectiveExecutionMode() == ExecutionMode.DEPLOYMENT
                && deploymentMetadata.desiredReplicas() == null) {
            ScalingConfig scaling = spec.scalingConfig();
            deploymentMetadata = deploymentMetadata.withDesiredReplicas(
                    scaling != null && scaling.minReplicas() != null ? Math.max(0, scaling.minReplicas()) : 1);
        }
    }

    public static RegisteredFunction nonManaged(FunctionSpec spec) {
        return new RegisteredFunction(spec, DeploymentMetadata.nonManaged(spec.executionMode(), spec.endpointUrl()));
    }

    public String name() {
        return spec.name();
    }

    public Integer desiredReplicas() {
        return deploymentMetadata.desiredReplicas();
    }

    public RegisteredFunction withDesiredReplicas(int desiredReplicas) {
        return new RegisteredFunction(spec, deploymentMetadata.withDesiredReplicas(desiredReplicas));
    }

    /**
     * Copies this function with the endpoint and object names a reconcile returned from the same
     * backend. The persisted backend id and effective execution mode are load-bearing: a result that
     * reports a different backend or mode means the recorded deployment no longer matches reality, so
     * it is rejected rather than silently adopted.
     */
    public RegisteredFunction withProvisionResult(ProvisionResult result) {
        if (result.backendId() != null && !result.backendId().equals(deploymentMetadata.deploymentBackend())) {
            throw new IllegalStateException("Reconcile returned backend '" + result.backendId()
                    + "' but function '" + name() + "' is managed by '"
                    + deploymentMetadata.deploymentBackend() + "'");
        }
        if (result.effectiveExecutionMode() != deploymentMetadata.effectiveExecutionMode()) {
            throw new IllegalStateException("Reconcile returned execution mode '" + result.effectiveExecutionMode()
                    + "' but function '" + name() + "' runs in '"
                    + deploymentMetadata.effectiveExecutionMode() + "'");
        }
        FunctionSpec refreshedSpec = spec.withEndpoint(result.endpointUrl(), result.effectiveExecutionMode());
        DeploymentMetadata refreshedMetadata = new DeploymentMetadata(
                deploymentMetadata.requestedExecutionMode(),
                deploymentMetadata.effectiveExecutionMode(),
                deploymentMetadata.deploymentBackend(),
                deploymentMetadata.degradationReason(),
                result.endpointUrl(),
                result.deploymentObjects(),
                deploymentMetadata.desiredReplicas());
        return new RegisteredFunction(refreshedSpec, refreshedMetadata);
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
