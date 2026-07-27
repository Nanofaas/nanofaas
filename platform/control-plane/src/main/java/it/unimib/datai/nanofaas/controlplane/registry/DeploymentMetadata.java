package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;

import java.util.Map;

public record DeploymentMetadata(
        ExecutionMode requestedExecutionMode,
        ExecutionMode effectiveExecutionMode,
        String deploymentBackend,
        String degradationReason,
        String effectiveEndpointUrl,
        Map<String, String> deploymentObjects
) {
    public DeploymentMetadata {
        deploymentObjects = deploymentObjects == null ? Map.of() : Map.copyOf(deploymentObjects);
    }

    public DeploymentMetadata(ExecutionMode requestedExecutionMode,
                              ExecutionMode effectiveExecutionMode,
                              String deploymentBackend,
                              String degradationReason) {
        this(requestedExecutionMode, effectiveExecutionMode, deploymentBackend, degradationReason, null);
    }

    public DeploymentMetadata(ExecutionMode requestedExecutionMode,
                              ExecutionMode effectiveExecutionMode,
                              String deploymentBackend,
                              String degradationReason,
                              String effectiveEndpointUrl) {
        this(requestedExecutionMode, effectiveExecutionMode, deploymentBackend, degradationReason,
                effectiveEndpointUrl, Map.of());
    }

    public static DeploymentMetadata nonManaged(ExecutionMode mode) {
        return new DeploymentMetadata(mode, mode, null, null, null);
    }

    public static DeploymentMetadata nonManaged(ExecutionMode mode, String effectiveEndpointUrl) {
        return new DeploymentMetadata(mode, mode, null, null, effectiveEndpointUrl);
    }
}
