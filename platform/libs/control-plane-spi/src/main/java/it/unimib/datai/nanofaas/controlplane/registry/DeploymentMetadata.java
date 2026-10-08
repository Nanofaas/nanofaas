package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;

import java.util.Map;

public record DeploymentMetadata(
        ExecutionMode requestedExecutionMode,
        ExecutionMode effectiveExecutionMode,
        String deploymentBackend,
        String degradationReason,
        String effectiveEndpointUrl,
        Map<String, String> deploymentObjects,
        Integer desiredReplicas
) {
    public DeploymentMetadata {
        deploymentObjects = deploymentObjects == null ? Map.of() : Map.copyOf(deploymentObjects);
        if (desiredReplicas != null && desiredReplicas < 0) {
            throw new IllegalArgumentException("desiredReplicas must be non-negative");
        }
        if (desiredReplicas != null && effectiveExecutionMode != ExecutionMode.DEPLOYMENT) {
            throw new IllegalArgumentException("desiredReplicas is only valid for DEPLOYMENT mode");
        }
    }

    public DeploymentMetadata(ExecutionMode requestedExecutionMode,
                              ExecutionMode effectiveExecutionMode,
                              String deploymentBackend,
                              String degradationReason) {
        this(requestedExecutionMode, effectiveExecutionMode, deploymentBackend, degradationReason, null, Map.of(), null);
    }

    public DeploymentMetadata(ExecutionMode requestedExecutionMode,
                              ExecutionMode effectiveExecutionMode,
                              String deploymentBackend,
                              String degradationReason,
                              String effectiveEndpointUrl) {
        this(requestedExecutionMode, effectiveExecutionMode, deploymentBackend, degradationReason,
                effectiveEndpointUrl, Map.of(), null);
    }

    public DeploymentMetadata(ExecutionMode requestedExecutionMode,
                              ExecutionMode effectiveExecutionMode,
                              String deploymentBackend,
                              String degradationReason,
                              String effectiveEndpointUrl,
                              Map<String, String> deploymentObjects) {
        this(requestedExecutionMode, effectiveExecutionMode, deploymentBackend, degradationReason,
                effectiveEndpointUrl, deploymentObjects, null);
    }

    public static DeploymentMetadata nonManaged(ExecutionMode mode) {
        return new DeploymentMetadata(mode, mode, null, null, null);
    }

    public static DeploymentMetadata nonManaged(ExecutionMode mode, String effectiveEndpointUrl) {
        return new DeploymentMetadata(mode, mode, null, null, effectiveEndpointUrl);
    }

    public DeploymentMetadata withDesiredReplicas(int desiredReplicas) {
        return new DeploymentMetadata(requestedExecutionMode, effectiveExecutionMode, deploymentBackend,
                degradationReason, effectiveEndpointUrl, deploymentObjects, desiredReplicas);
    }
}
