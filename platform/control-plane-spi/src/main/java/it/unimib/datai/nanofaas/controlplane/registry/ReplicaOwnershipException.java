package it.unimib.datai.nanofaas.controlplane.registry;
public final class ReplicaOwnershipException extends IllegalStateException {
    public ReplicaOwnershipException(String function) { super("Replica control is exclusively owned for function '"+function+"'"); }
}
