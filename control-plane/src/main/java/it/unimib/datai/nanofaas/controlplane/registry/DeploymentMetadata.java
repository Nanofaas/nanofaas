package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;

public record DeploymentMetadata(
        ExecutionMode requestedExecutionMode,
        ExecutionMode effectiveExecutionMode,
        String deploymentBackend,
        String degradationReason,
        String effectiveEndpointUrl
) {
    public DeploymentMetadata(ExecutionMode requestedExecutionMode,
                              ExecutionMode effectiveExecutionMode,
                              String deploymentBackend,
                              String degradationReason) {
        this(requestedExecutionMode, effectiveExecutionMode, deploymentBackend, degradationReason, null);
    }

    public static DeploymentMetadata nonManaged(ExecutionMode mode) {
        return new DeploymentMetadata(mode, mode, null, null, null);
    }

    public static DeploymentMetadata nonManaged(ExecutionMode mode, String effectiveEndpointUrl) {
        return new DeploymentMetadata(mode, mode, null, null, effectiveEndpointUrl);
    }
}
