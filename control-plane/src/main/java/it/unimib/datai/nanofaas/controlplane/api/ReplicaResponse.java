package it.unimib.datai.nanofaas.controlplane.api;

public record ReplicaResponse(
        String function,
        int replicas
) {
}
