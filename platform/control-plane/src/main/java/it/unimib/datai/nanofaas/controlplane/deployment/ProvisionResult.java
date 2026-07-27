package it.unimib.datai.nanofaas.controlplane.deployment;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;

import java.util.Map;

/**
 * What a deployment backend produced for a function.
 *
 * <p>{@code deploymentObjects} names the objects the backend actually created, so
 * a client can address them without rebuilding the backend's naming convention.
 * Reconstructing {@code fn-<name>} outside the control plane is a guess that
 * survives only until a backend changes its prefix or normalises a name.
 */
public record ProvisionResult(
        String endpointUrl,
        String backendId,
        ExecutionMode effectiveExecutionMode,
        String degradationReason,
        Map<String, String> deploymentObjects
) {
    /** Kubernetes: the Deployment carrying the function's pods. */
    public static final String DEPLOYMENT = "deployment";
    /** Kubernetes: the Service fronting them. */
    public static final String SERVICE = "service";
    /** Kubernetes: the namespace both live in, as the control plane resolved it. */
    public static final String NAMESPACE = "namespace";
    /**
     * Container backends: the replica containers are {@code <prefix>-r<index>}.
     * The prefix rather than the names because replicas come and go with scaling,
     * while this map is captured once at registration.
     */
    public static final String CONTAINER_NAME_PREFIX = "containerNamePrefix";

    public ProvisionResult {
        deploymentObjects = deploymentObjects == null ? Map.of() : Map.copyOf(deploymentObjects);
    }

    public ProvisionResult(String endpointUrl, String backendId) {
        this(endpointUrl, backendId, Map.of());
    }

    public ProvisionResult(String endpointUrl, String backendId, Map<String, String> deploymentObjects) {
        this(endpointUrl, backendId, ExecutionMode.DEPLOYMENT, null, deploymentObjects);
    }
}
