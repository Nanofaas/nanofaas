package it.unimib.datai.nanofaas.controlplane.deployment;

public record ManagedDeploymentTarget(String functionName, String backendId) {
    public ManagedDeploymentTarget {
        if (functionName == null || functionName.isBlank() || backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException("functionName and backendId are required");
        }
    }
}
