package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;

public record DeploymentMetadata(
        ExecutionMode requestedExecutionMode,
        ExecutionMode effectiveExecutionMode,
        String deploymentBackend,
        String degradationReason
) {
    public static DeploymentMetadata nonManaged(ExecutionMode mode) {
        return new DeploymentMetadata(mode, mode, null, null);
    }
}
