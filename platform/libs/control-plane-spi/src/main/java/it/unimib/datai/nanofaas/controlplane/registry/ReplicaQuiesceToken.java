package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaAdmissionToken;
import java.util.Objects;

/** Binds a provider's pending admission capability to its exact replica writer and deployment. */
public record ReplicaQuiesceToken(ReplicaControlLease lease, ManagedDeploymentTarget target,
                                 ReplicaAdmissionToken admission) {
    public ReplicaQuiesceToken {
        Objects.requireNonNull(lease);
        Objects.requireNonNull(target);
        Objects.requireNonNull(admission);
        if (!lease.generation().functionName().equals(target.functionName())) {
            throw new IllegalArgumentException("lease and deployment must name the same function");
        }
    }

    public String admissionEpoch() {
        return admission.admissionEpoch();
    }
}
