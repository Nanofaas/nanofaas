package it.unimib.datai.nanofaas.modules.offload.oneshot.actuation;

import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.registry.*;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.deployment.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.auction.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.*;
import it.unimib.datai.nanofaas.p2papi.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.*;
import org.springframework.context.SmartLifecycle;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Sole replica writer. Uncertain preparation retains ownership and announced capacity until drain. */
public final class ReplicaPlanActuator implements SmartLifecycle,AutoCloseable {
    public static final String READY_TOPIC="nanofaas.oneshot.ready.v1";
    private static final class Preparation {
        final EpochOutcome outcome; final AuctionSnapshot snapshot; final long deadline;
        final Map<String,Assignment> received=new ConcurrentHashMap<>(),acknowledged=new ConcurrentHashMap<>();
        final Map<String,Assignment> committedInbound=new ConcurrentHashMap<>();
        volatile Map<String,Assignment> localReady=Map.of(); volatile boolean closed,failed;
        final Set<String> receivedClosures=ConcurrentHashMap.newKeySet();
        Preparation(EpochOutcome outcome,long deadline) { this.outcome=outcome; snapshot=outcome.snapshot();this.deadline=deadline; }
    }
    private final ManagedReplicaControl control; private final FunctionCatalogView catalog; private final PeerTransport peers;
    private final Supplier<Instant> now; private final BooleanSupplier clockHealthy;
    private final Duration preparationBudget,drainGrace;
    private final String owner="one-shot:"+UUID.randomUUID();
    private final Map<String,ManagedDeploymentTarget> leaseTargets=new ConcurrentHashMap<>();
    private final boolean enabled;
    private final Map<String,ReplicaControlLease> leases=new ConcurrentHashMap<>();
    private final AtomicReference<Instant> reservedUntil=new AtomicReference<>(Instant.MIN);
    private final AtomicReference<Preparation> preparing=new AtomicReference<>(),receiving=new AtomicReference<>();
    private final AtomicReference<ActiveRoutingPlan> active=new AtomicReference<>(),pending=new AtomicReference<>();
    private final AuctionCodec codec=new AuctionCodec(); private final Scheduler scheduler=Schedulers.newBoundedElastic(1,1,"one-shot-actuation");
    private volatile PeerSubscription subscription; private volatile boolean running; private volatile long lifecycle;
    public ReplicaPlanActuator(ManagedReplicaControl control,FunctionCatalogView catalog,PeerTransport peers,Supplier<Instant> now,BooleanSupplier clockHealthy,Duration preparationBudget,Duration drainGrace) {
        this(control,catalog,peers,now,clockHealthy,preparationBudget,drainGrace,true);
    }
    public ReplicaPlanActuator(ManagedReplicaControl control,FunctionCatalogView catalog,PeerTransport peers,Supplier<Instant> now,BooleanSupplier clockHealthy,Duration preparationBudget,Duration drainGrace,boolean enabled) {
        this.enabled=enabled;
        this.control=control;this.catalog=catalog;this.peers=peers;this.now=now;this.clockHealthy=clockHealthy;this.preparationBudget=preparationBudget;this.drainGrace=drainGrace;
        if(preparationBudget.isZero() || preparationBudget.isNegative() || drainGrace.isNegative()) throw new IllegalArgumentException("invalid actuation duration");
    }
    @Override public boolean isAutoStartup() { return enabled; }
    @Override public synchronized void start() {
        if(running || !enabled) return; long token=++lifecycle;
        subscription=peers.subscribe(READY_TOPIC,(sender,bytes)->Mono.fromCallable(()->running && lifecycle==token?receive(sender,bytes):new byte[]{0}));running=true;
    }
    @Override public synchronized void stop() { running=false;lifecycle++;if(subscription!=null) subscription.close();pending.set(null); }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE-2045; }
    @Override public void close() { stop();scheduler.dispose(); }
    public Optional<ActiveRoutingPlan> activePlan() {
        var next=pending.get();var time=now.get();
        if(next!=null && next.validAt(time) && validOwnership(next)) {
            if(pending.compareAndSet(next,null)) active.set(next);
        }
        var plan=active.get(); return plan!=null && plan.validAt(time) && validOwnership(plan)?Optional.of(plan):Optional.empty();
    }
    private boolean validOwnership(ActiveRoutingPlan plan) {
        if(!running || !clockHealthy.getAsBoolean() || peers.localEndpoint().filter(e->e.peerId().equals(plan.nodeId()) && e.incarnation().equals(plan.incarnation())).isEmpty()) return false;
        return plan.functions().entrySet().stream().allMatch(e->{ var lease=leases.get(e.getKey());return lease!=null && lease.generation().id()==e.getValue().generation() && control.ownsReplicaLease(lease) && generation(e.getKey(),e.getValue().generation()); });
    }
    private RegisteredFunction function(String name) { return catalog.listRegistered().stream().filter(f->f.name().equals(name)).findFirst().orElseThrow(()->new IllegalStateException("function removed: "+name)); }
    private boolean generation(String name,long id) {
        try { var gen=control.generationOf(function(name));return gen!=null && gen.id()==id; } catch(IllegalStateException removed) { return false; }
    }
    private void check(Preparation p) {
        if(!running || !clockHealthy.getAsBoolean() || Thread.currentThread().isInterrupted() || System.nanoTime()>=p.deadline || !now.get().isBefore(p.snapshot.validFrom())) throw new IllegalStateException("preparation expired or clock unavailable");
        var local=peers.localEndpoint().orElseThrow();if(!local.peerId().equals(p.snapshot.nodeId()) || !local.incarnation().equals(p.snapshot.incarnation())) throw new IllegalStateException("local incarnation changed");
        p.snapshot.identities().forEach((name,id)-> { if(!generation(name,id.generation())) throw new IllegalStateException("function generation changed"); });
    }
    public Mono<PlanActivation> prepare(EpochOutcome outcome) { return prepare(outcome,preparationBudget); }
    public Mono<PlanActivation> prepare(EpochOutcome outcome,Duration budget) {
        if(budget==null || budget.isZero() || budget.isNegative() || budget.compareTo(Duration.ofHours(1))>0) throw new IllegalArgumentException("invalid preparation budget");
        return Mono.defer(()-> {
            if(outcome.status()!=EpochOutcome.Status.CONVERGED || outcome.snapshot()==null || outcome.input()==null) return Mono.just(failed("only converged frozen input can prepare"));
            activePlan();
            var current=active.get();
            if((current!=null && outcome.snapshot().validFrom().isBefore(current.endsAt())) || outcome.snapshot().validFrom().isBefore(reservedUntil.get())) return Mono.just(failed("epoch windows overlap reserved commitments"));
            var waiting=pending.get();if(waiting!=null && now.get().isBefore(waiting.endsAt())) return Mono.just(failed("a prepared plan already exists"));
            long until=Duration.between(now.get(),outcome.snapshot().validFrom()).toNanos();
            var p=new Preparation(outcome,System.nanoTime()+Math.min(budget.toNanos(),Math.max(0,until)));
            if(!preparing.compareAndSet(null,p)) return Mono.just(failed("preparation already active"));
            receiving.set(p);
            return Mono.using(()->p,
                    preparation->Mono.fromCallable(()->apply(preparation)).subscribeOn(scheduler),
                    preparation->preparing.compareAndSet(preparation,null),true);
        });
    }
    private PlanActivation failed(String reason) { return new PlanActivation(PlanActivation.Status.FAILED,null,Map.of(),Map.of(),reason,now.get()); }
    private void validate(Preparation p) {
        var selected=p.snapshot.identities().keySet();
        if(!selected.containsAll(leases.keySet())) throw new IllegalArgumentException("drain removed functions before changing scope");
        for(var row:p.snapshot.baseProblem().functions()) {
            var f=function(row.id());var spec=f.spec();var cc=spec.scalingConfig()==null?null:spec.scalingConfig().concurrencyControl();
            if((spec.concurrency()==null || spec.concurrency()<p.snapshot.desiredReplicas().getOrDefault(row.id(),0)) || spec.env()==null || !"true".equals(spec.env().get("NANOFAAS_ONE_SHOT_PROFILE")) || !"1".equals(spec.env().get("NANOFAAS_MAX_CONCURRENT_HANDLERS"))
                || cc==null || cc.mode()!=ConcurrencyControlMode.STATIC_PER_POD || !Integer.valueOf(1).equals(cc.targetInFlightPerPod()) || spec.scalingConfig().strategy()==ScalingStrategy.HPA
                || spec.resources()==null || spec.resources().limits()==null || !Long.valueOf(row.memoryMiB()).equals(spec.resources().limits().memoryMiB()==null?null:spec.resources().limits().memoryMiB().longValue())
                || !control.supportsPhysicalReplicaControl(f.managedDeploymentTarget().orElseThrow())) throw new IllegalArgumentException("function not eligible for physical one-shot: "+row.id());
        }
    }
    private ReplicaObservation.Available observation(Preparation p,ManagedDeploymentTarget target) throws InterruptedException {
        while(true) {
            check(p);var observed=control.observeReplicaStatus(target);
            if(observed instanceof ReplicaObservation.Available available && available.state()==ReplicaObservation.State.FRESH) return available;
            Thread.sleep(20);
        }
    }
    private PlanActivation apply(Preparation p) {
        var desired=new LinkedHashMap<String,Integer>();var ready=new LinkedHashMap<String,Integer>();
        try {
            check(p);validate(p);var rows=p.snapshot.baseProblem().functions();var targets=new HashMap<String,ManagedDeploymentTarget>();
            Duration ttl=Duration.between(now.get(),p.snapshot.validUntil()).plus(drainGrace);
            for(var row:rows) {
                var id=p.snapshot.identities().get(row.id());var existing=leases.get(row.id());
                if(existing!=null && existing.generation().id()!=id.generation()) { leases.remove(row.id(),existing);existing=null; }
                var acquired=existing==null?control.acquireReplicaLease(new FunctionGeneration(row.id(),id.generation()),owner,ttl):control.renewReplicaLease(existing,ttl);
                leases.put(row.id(),acquired.orElseThrow(()->new IllegalStateException("replica ownership unavailable: "+row.id())));
                targets.put(row.id(),function(row.id()).managedDeploymentTarget().orElseThrow());leaseTargets.put(row.id(),targets.get(row.id()));
            }
            var current=new LinkedHashMap<String,Integer>(); long used=0;
            for(var row:rows) { int count=observation(p,targets.get(row.id())).status().desiredReplicas();current.put(row.id(),count);used=Math.addExact(used,Math.multiplyExact(row.memoryMiB(),count)); }
            long budget=p.snapshot.baseProblem().memoryCapacityMiB();if(used>budget) throw new IllegalStateException("existing physical memory exceeds budget");
            var old=active.get();boolean protectedOld=(old!=null && now.get().isBefore(old.endsAt())) || now.get().isBefore(reservedUntil.get());
            // Drain reductions before allocating increases. A live epoch retains its immutable capacity.
            for(var row:rows) {
                int requested=p.snapshot.desiredReplicas().get(row.id());int count=current.get(row.id());
                int minimum=0;
                if(protectedOld && old!=null && now.get().isBefore(old.endsAt())) {
                    var prior=old.functions().get(row.id());
                    if(prior!=null) {
                        double rate=prior.localRate()+prior.inbound().stream().mapToLong(Assignment::quantity).sum()*old.flowQuantum();
                        minimum=rate==0?0:(int)Math.max(1,Math.ceil(rate*prior.demandSeconds()/prior.utilization()-1e-9));
                    }
                }
                if(now.get().isBefore(reservedUntil.get())) minimum=Math.max(minimum,count);
                int target=Math.max(minimum,requested);
                if(target<count) {
                    if(!control.setReplicas(leases.get(row.id()),targets.get(row.id()),target)) throw new IllegalStateException("leased downscale rejected");
                    int actual=observation(p,targets.get(row.id())).status().desiredReplicas();
                    used-=Math.multiplyExact(count-actual,row.memoryMiB());current.put(row.id(),actual);
                }
                desired.put(row.id(),target);
            }
            if(used>budget) throw new IllegalStateException("physical overlap memory exceeds budget");
            for(var row:rows) {
                int count=current.get(row.id());int target=desired.get(row.id());
                int growth=(int)Math.min(Math.max(0,target-count),(budget-used)/row.memoryMiB());target=count+growth;
                if(target!=count && !control.setReplicas(leases.get(row.id()),targets.get(row.id()),target)) throw new IllegalStateException("leased upscale rejected");
                used+=growth*row.memoryMiB();desired.put(row.id(),target);
            }
            for(var row:rows) {
                int count=Math.min(desired.get(row.id()),observation(p,targets.get(row.id())).status().readyReplicas());
                if(!control.setReadyConcurrency(leases.get(row.id()),count)) throw new IllegalStateException("ready admission fence rejected: "+row.id());
                ready.put(row.id(),count);
            }
            var localReady=new LinkedHashMap<String,Assignment>();var localRates=new HashMap<String,Double>();double quantum=p.outcome.input().flowQuantum();
            for(var row:rows) {
                long capacity=(long)Math.min(9007199254740991d,Math.floor(ready.get(row.id())*row.utilization()/row.demandSeconds()));
                var inbound=p.snapshot.assignments().values().stream().filter(a->a.sellerId().equals(p.snapshot.nodeId()) && a.function().equals(row.id())).sorted(Comparator.comparing(Assignment::id)).toList();
                for(var grant:inbound) { long quantity=Math.min(grant.quantity(),capacity);if(grant.readyConfirmed() && quantity<grant.quantity()) throw new IllegalStateException("confirmed inbound cannot be revoked");var confirmation=grant.confirm(quantity);localReady.put(grant.id(),confirmation);capacity-=quantity; }
                localRates.put(row.id(),Math.min(row.fixedLocal(),capacity)*quantum);
            }
            p.localReady=Map.copyOf(localReady);
            if(localReady.values().stream().anyMatch(a->a.quantity()>0)) {
                 reservedUntil.accumulateAndGet(p.snapshot.validUntil(),(a,b)->a.isAfter(b)?a:b);
            }
            boolean degraded=!desired.equals(p.snapshot.desiredReplicas());
            for(var peer:p.snapshot.peerIncarnations().keySet()) {
                var confirmations=localReady.values().stream().filter(a->a.buyerId().equals(peer)).toList();
                if(!send(p,peer,AuctionCodec.Phase.READY,confirmations)) degraded=true;
            }
            // A buyer routes only after the seller acknowledges its matching READY_ACK.
            for(var peer:p.snapshot.peerIncarnations().keySet()) {
                while(now.get().isBefore(p.snapshot.validFrom()) && System.nanoTime()<p.deadline-Math.min(Duration.ofMillis(100).toNanos(),preparationBudget.toNanos()/10) && !receivedForSeller(p,peer)) Thread.sleep(20);
                if(!receivedForSeller(p,peer)) degraded=true;
                var confirmations=p.received.values().stream().filter(a->a.sellerId().equals(peer)).toList();
                if(!confirmations.isEmpty() && send(p,peer,AuctionCodec.Phase.READY_ACK,confirmations)) confirmations.forEach(a->p.acknowledged.put(a.id(),a));
            }
            check(p);var plans=new LinkedHashMap<String,ActiveRoutingPlan.FunctionPlan>();
            for(var row:rows) {
                var id=p.snapshot.identities().get(row.id());var inbound=localReady.values().stream().filter(a->a.function().equals(row.id())).toList();
                var outbound=p.acknowledged.values().stream().filter(a->a.function().equals(row.id())).toList();
                double forecast=p.outcome.input().forecasts().get(row.id()).rate();double local=localRates.get(row.id());double remote=outbound.stream().mapToLong(Assignment::quantity).sum()*quantum;
                double cloud=Math.max(0,forecast-local-remote);
                if(ready.get(row.id())<p.snapshot.desiredReplicas().get(row.id()) || outbound.stream().mapToLong(Assignment::quantity).sum()<p.snapshot.assignments().values().stream().filter(a->a.buyerId().equals(p.snapshot.nodeId()) && a.function().equals(row.id())).mapToLong(Assignment::quantity).sum()) degraded=true;
                plans.put(row.id(),new ActiveRoutingPlan.FunctionPlan(id.version(),id.generation(),ready.get(row.id()),local,cloud,forecast,row.memoryMiB(),row.demandSeconds()/quantum,row.utilization(),inbound,outbound));
            }
            var plan=new ActiveRoutingPlan(p.snapshot.nodeId(),p.snapshot.incarnation(),p.snapshot.epoch(),p.snapshot.revision(),p.snapshot.validFrom(),p.snapshot.validUntil(),quantum,plans);
            pending.set(plan);p.closed=true;
            return new PlanActivation(degraded?PlanActivation.Status.DEGRADED:PlanActivation.Status.PREPARED,plan,desired,ready,"ready subset; unacknowledged outbound goes to terminal cloud",now.get());
        } catch(InterruptedException | RuntimeException failure) { if(failure instanceof InterruptedException) Thread.currentThread().interrupt(); p.failed=true;p.closed=true;return new PlanActivation(PlanActivation.Status.FAILED,null,desired,ready,failure.getMessage(),now.get()); }
    }
    private boolean receivedForSeller(Preparation p,String peer) { return p.receivedClosures.contains(peer); }
    private byte[] receive(String sender,byte[] bytes) {
        var p=receiving.get();if(p==null || !clockHealthy.getAsBoolean()) return new byte[]{0};
        AuctionCodec.Batch batch;try { batch=codec.decode(bytes); } catch(IllegalArgumentException invalid) { return new byte[]{0}; }
        if(!Objects.equals(p.snapshot.peerIncarnations().get(sender),batch.incarnation()) || !sender.equals(batch.senderId()) || batch.epoch()!=p.snapshot.epoch() || batch.round()!=p.snapshot.round()
            || Double.compare(batch.flowQuantum(),p.outcome.input().flowQuantum())!=0 || !batch.startsAt().equals(p.snapshot.validFrom()) || !batch.endsAt().equals(p.snapshot.validUntil()) || !now.get().isBefore(p.snapshot.validFrom()) || p.failed) return new byte[]{0};
        synchronized(p) {
            for(var message:batch.messages()) {
                var a=message.assignment();if(a==null || !a.readyConfirmed() || !message.target().equals(p.snapshot.nodeId())) return new byte[]{0};
                var original=p.snapshot.assignments().get(a.id());
                if(original==null || a.quantity()>original.quantity() || !original.confirm(a.quantity()).equals(a) || !generation(a.function(),p.snapshot.identities().get(a.function()).generation())) return new byte[]{0};
                if(batch.phase()==AuctionCodec.Phase.READY) { if(p.closed && !a.equals(p.received.get(a.id()))) return new byte[]{0}; if(!a.sellerId().equals(sender) || !a.buyerId().equals(p.snapshot.nodeId())) return new byte[]{0};var prior=p.received.get(a.id());if(prior!=null && !prior.equals(a)) return new byte[]{0}; }
                else if(batch.phase()==AuctionCodec.Phase.READY_ACK) { if(!a.buyerId().equals(sender) || !a.sellerId().equals(p.snapshot.nodeId()) || !a.equals(p.localReady.get(a.id()))) return new byte[]{0}; }
                else return new byte[]{0};
            }
            if(batch.phase()==AuctionCodec.Phase.READY) { batch.messages().forEach(m->p.received.put(m.assignment().id(),m.assignment()));p.receivedClosures.add(sender); }
            else if(batch.phase()==AuctionCodec.Phase.READY_ACK) batch.messages().forEach(m->p.committedInbound.put(m.assignment().id(),m.assignment()));
            else return new byte[]{0};
        }
        return new byte[]{1};
    }
    private boolean send(Preparation p,String peer,AuctionCodec.Phase phase,List<Assignment> assignments) throws InterruptedException {
        var messages=assignments.stream().map(a->new AuctionMessage(new AuctionMessage.Envelope(1,p.snapshot.nodeId(),p.snapshot.incarnation(),p.snapshot.epoch(),p.snapshot.round(),phase+":"+a.id(),p.snapshot.revision(),p.snapshot.validFrom(),p.snapshot.validUntil()),AuctionMessage.Kind.READY_CONFIRM,peer,null,null,a)).toList();
        byte[] bytes=codec.encode(new AuctionCodec.Batch(1,p.snapshot.nodeId(),p.snapshot.incarnation(),p.snapshot.epoch(),p.snapshot.round(),phase,p.snapshot.validFrom(),p.snapshot.validUntil(),false,messages,p.outcome.input().flowQuantum()));
        long sendDeadline=Math.min(p.deadline-Math.min(Duration.ofMillis(100).toNanos(),preparationBudget.toNanos()/10),System.nanoTime()+Duration.ofMillis(500).toNanos());
        while(System.nanoTime()<sendDeadline && now.get().isBefore(p.snapshot.validFrom())) {
            check(p);var timeout=Duration.ofNanos(Math.max(1,Math.min(Duration.ofMillis(200).toNanos(),sendDeadline-System.nanoTime())));
            try { var reply=peers.request(peer,READY_TOPIC,bytes,timeout).block(timeout);if(reply!=null && reply.length==1 && reply[0]==1) return true; }
            catch(RuntimeException unavailable) { if(!running) return false; }
            Thread.sleep(20);
        }
        return false;
    }
    public Set<String> ownedFunctions() { return Set.copyOf(leases.keySet()); }
    public Mono<Boolean> drainAndRelease() {
        return Mono.fromCallable(()-> {
            if(now.get().isBefore(reservedUntil.get())) return false;
            var plan=active.get();if(plan!=null && now.get().isBefore(plan.endsAt())) return false;
            if(pending.get()!=null && now.get().isBefore(pending.get().endsAt())) return false;
            for(var entry:List.copyOf(leases.entrySet())) {
                var target=leaseTargets.get(entry.getKey());
                if(target==null) return false;
                if(!control.drainAndReleaseReplicaLease(entry.getValue(),target)) return false;leases.remove(entry.getKey(),entry.getValue());leaseTargets.remove(entry.getKey());
            }
            active.set(null);pending.set(null);return true;
        }).subscribeOn(scheduler);
    }
}
