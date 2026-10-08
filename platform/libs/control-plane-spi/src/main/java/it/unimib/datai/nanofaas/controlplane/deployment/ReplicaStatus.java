package it.unimib.datai.nanofaas.controlplane.deployment;

public record ReplicaStatus(int desiredReplicas, int readyReplicas) {
    public ReplicaStatus {
        if (desiredReplicas < 0 || readyReplicas < 0) {
            throw new IllegalArgumentException("replica counts must be non-negative");
        }
    }
}
