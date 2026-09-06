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
     * objects untouched. It must never delegate to {@link #provision(FunctionSpec)}.
     */
    ProvisionResult reconcile(FunctionSpec spec,
                              int desiredReplicas,
                              Map<String, String> deploymentObjects);

    /**
     * Applies a changed spec to an already-provisioned function. Registration hands the backend a
     * spec once; a later {@code PATCH} of the function's tuning (timeout, concurrency) must reach
     * whatever the backend derived from it, or the deployment keeps enforcing the values it was
     * born with. Ignored for a name this backend does not hold.
     *
     * <p>The default is a no-op: a backend that reads the spec fresh on every dispatch has nothing
     * cached to refresh. Backends that snapshot it — the container-local proxy derives its
     * single-hop timeout and admission bound from it — must override.
     */
    default void updateSpec(FunctionSpec spec) {
        // Nothing derived from the spec is held between calls.
    }

    void deprovision(String functionName);

    void setReplicas(String functionName, int replicas);

    int getReadyReplicas(String functionName);

    default ReplicaStatus getReplicaStatus(String functionName) {
        int readyReplicas = getReadyReplicas(functionName);
        return new ReplicaStatus(readyReplicas, readyReplicas);
    }
}
