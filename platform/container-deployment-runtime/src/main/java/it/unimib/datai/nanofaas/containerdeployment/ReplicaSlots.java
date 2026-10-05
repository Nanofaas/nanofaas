package it.unimib.datai.nanofaas.containerdeployment;
import java.util.*;
/** Pool mutations and dispatch share one lock. Occupied or draining entries survive pool updates. */
public final class ReplicaSlots {
    private static final class Slot {
        final String incarnation; boolean draining; ReplicaLease lease;
        Slot(String incarnation) { this.incarnation=incarnation; }
    }
    private final Map<String, Slot> slots = new LinkedHashMap<>();
    public synchronized void update(Map<String,String> readyBackends) {
        slots.forEach((id, slot) -> { if (!readyBackends.containsKey(id)) slot.draining=true; });
        slots.entrySet().removeIf(e -> !readyBackends.containsKey(e.getKey()) && e.getValue().lease==null);
        readyBackends.forEach((id, incarnation) -> {
            if (id==null || incarnation==null || incarnation.isBlank()) throw new IllegalArgumentException("backend incarnation required");
            var prior=slots.get(id);
            if(prior==null) slots.put(id,new Slot(incarnation));
            else if(!prior.incarnation.equals(incarnation)) prior.draining=true;
        });
    }
    public Optional<ReplicaLease> tryAcquire(String executionId) { return tryAcquire(executionId,null); }
    public synchronized Optional<ReplicaLease> tryAcquire(String executionId,String attempt) {
        if(executionId==null || executionId.isBlank() || executionId.length()>256) throw new IllegalArgumentException("execution identity required");
        for(var entry: slots.entrySet()) if(!entry.getValue().draining && entry.getValue().lease==null) {
            var slot=entry.getValue(); slot.lease=new ReplicaLease(this,entry.getKey(),slot.incarnation,executionId,attempt);
            return Optional.of(slot.lease);
        }
        return Optional.empty();
    }
    synchronized boolean cancelUndispatched(ReplicaLease lease) {
        var slot=slots.get(lease.backend());
        if(slot==null || slot.lease!=lease) return false;
        slot.lease=null; return true;
    }
    synchronized boolean release(ReplicaLease lease, ExecutionObservation proof) {
        var slot=slots.get(lease.backend());
        if(slot==null || slot.lease!=lease || proof==null || !"RELEASED".equals(proof.state())
            || !Objects.equals(lease.incarnation(),proof.incarnation()) || !Objects.equals(lease.executionId(),proof.executionId())
            || !Objects.equals(lease.dispatchAttempt(),proof.dispatchAttempt())) return false;
        slot.lease=null; return true;
    }
    public synchronized void beginDrain(String backendId) { var slot=slots.get(backendId); if(slot!=null) slot.draining=true; }
    public synchronized boolean routable(String backendId) { var slot=slots.get(backendId); return slot!=null && !slot.draining; }
    public synchronized boolean drained(String backendId) { var slot=slots.get(backendId); return slot==null || slot.lease==null; }
    public synchronized List<ReplicaLease> occupied() { return slots.values().stream().map(s->s.lease).filter(Objects::nonNull).toList(); }
}
