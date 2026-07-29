package it.unimib.datai.nanofaas.controlplane.deployment;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;

public interface ManagedDeploymentProvider {
    String backendId();

    boolean isAvailable();

    boolean supports(FunctionSpec spec);

    ProvisionResult provision(FunctionSpec spec);

    void deprovision(String functionName);

    void setReplicas(String functionName, int replicas);

    int getReadyReplicas(String functionName);

    default ReplicaStatus getReplicaStatus(String functionName) {
        int readyReplicas = getReadyReplicas(functionName);
        return new ReplicaStatus(readyReplicas, readyReplicas);
    }
}
