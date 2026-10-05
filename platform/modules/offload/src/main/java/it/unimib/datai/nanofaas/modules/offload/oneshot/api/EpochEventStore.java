package it.unimib.datai.nanofaas.modules.offload.oneshot.api;
import java.time.Instant;
import java.util.*;
/** Node-global bounded event log; cursors never reset when old entries are evicted. */
public final class EpochEventStore {
    public record Event(int schemaVersion,String nodeId,String incarnation,long epoch,int round,String type,long monotonicOffsetNanos,Instant at,String status,boolean censored,String correlationId) {}
    public record Entry(long cursor,Event event,long operationalNanos) {}
    private final int capacity;private long cursor;private final ArrayDeque<Entry> events=new ArrayDeque<>();
    public EpochEventStore(int capacity) { if(capacity<1 || capacity>100000) throw new IllegalArgumentException("invalid retention");this.capacity=capacity; }
    public synchronized void record(long epoch,String state,boolean censored,long elapsed,Instant at) {
        if(epoch<0 || elapsed<0 || state==null || state.length()>256 || at==null) throw new IllegalArgumentException("invalid event");
        record("unbound","unbound",epoch,0,state,censored,elapsed,at);
    }
    public synchronized void record(String node,String incarnation,long epoch,int round,String state,boolean censored,long elapsed,Instant at) {
        events.addLast(new Entry(++cursor,new Event(1,node,incarnation,epoch,round,"epoch",elapsed,at,state,censored,node+":"+epoch),elapsed));while(events.size()>capacity) events.removeFirst();
    }
    public synchronized List<Entry> page(long epoch,long after,int limit) {
        if(after<0 || limit<1 || limit>1000) throw new IllegalArgumentException("invalid page");
        return events.stream().filter(e->e.event().epoch()==epoch && e.cursor()>after).limit(limit).toList();
    }
}
