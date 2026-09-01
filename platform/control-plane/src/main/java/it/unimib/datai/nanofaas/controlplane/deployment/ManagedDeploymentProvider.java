package it.unimib.datai.nanofaas.controlplane.deployment;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;

import java.util.Map;

public interface ManagedDeploymentProvider {
    String backendId();

    boolean isAvailable();

    boolean supports(FunctionSpec spec);

    ProvisionResult provision(FunctionSpec spec);

    /**
     * Restores persisted functions without destroying healthy resources: the
     * backend must reconcile its resources toward the persisted names and
     * replica target, creating only what is missing and leaving existing
     * objects untouched.
     *
     * <p>The default is a temporary safe guard until every backend implements
     * reconciliation. It must never delegate to {@link #provision(FunctionSpec)}.
     */
    default ProvisionResult reconcile(FunctionSpec spec,
                                      int desiredReplicas,
                                      Map<String, String> deploymentObjects) {
        throw new UnsupportedOperationException("Backend '" + backendId() + "' does not support reconciliation");
    }

    void deprovision(String functionName);

    void setReplicas(String functionName, int replicas);

    int getReadyReplicas(String functionName);

    default ReplicaStatus getReplicaStatus(String functionName) {
        int readyReplicas = getReadyReplicas(functionName);
        return new ReplicaStatus(readyReplicas, readyReplicas);
    }
}
