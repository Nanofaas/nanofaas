package it.unimib.datai.nanofaas.containerdeployment;
public final class ReplicaLease {
    private final ReplicaSlots owner;
    private boolean dispatched;
    private final String backend, incarnation, executionId, dispatchAttempt;
    ReplicaLease(ReplicaSlots owner, String backend, String incarnation, String executionId, String dispatchAttempt) {
        this.owner=owner; this.backend=backend; this.incarnation=incarnation; this.executionId=executionId; this.dispatchAttempt=dispatchAttempt;
    }
    public String backend() { return backend; }
    public String incarnation() { return incarnation; }
    public String executionId() { return executionId; }
    public String dispatchAttempt() { return dispatchAttempt; }
    public synchronized void markDispatched() { dispatched=true; }
    public synchronized boolean cancelBeforeDispatch() { return !dispatched && owner.cancelUndispatched(this); }
    public boolean markReleased(ExecutionObservation proof) { return owner.release(this, proof); }
}
