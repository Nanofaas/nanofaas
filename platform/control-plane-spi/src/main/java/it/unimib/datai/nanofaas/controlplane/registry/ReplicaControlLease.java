package it.unimib.datai.nanofaas.controlplane.registry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
/** Internal replica-writer capability. Expiry fences writes but is never a physical release proof. */
public record ReplicaControlLease(FunctionGeneration generation,String token,String owner,long deadlineNanos) {
    public ReplicaControlLease {
        java.util.Objects.requireNonNull(generation);
        if(token==null || token.isBlank() || owner==null || owner.isBlank()) throw new IllegalArgumentException("lease identity required");
    }
}
