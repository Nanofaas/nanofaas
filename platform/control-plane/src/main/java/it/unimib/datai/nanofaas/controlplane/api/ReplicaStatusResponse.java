package it.unimib.datai.nanofaas.controlplane.api;

public record ReplicaStatusResponse(
        String name,
        int desiredReplicas,
        int readyReplicas
) {
}
