package it.unimib.datai.nanofaas.modules.offload.oneshot.actuation;
import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.registry.*;
import it.unimib.datai.nanofaas.controlplane.capacity.*;
import it.unimib.datai.nanofaas.controlplane.deployment.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.auction.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.solver.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.*;
import it.unimib.datai.nanofaas.p2papi.*;
import it.unimib.datai.nanofaas.forecastingapi.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.time.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ReplicaPlanActuatorTest {
    @Test void fractionalContiguousPlansPrepareWithoutOverlap() {
        var now=new AtomicReference<>(Instant.parse("2026-10-04T10:00:00.000123456Z"));
        var from=now.get().plusSeconds(5);var period=Duration.ofSeconds(300).plusNanos(500_000);
        try(var actuator=new ReplicaPlanActuator(control(3,2),()->List.of(registered("f",1)),peers("a"),now::get,()->true,Duration.ofSeconds(2),Duration.ofSeconds(1))) {
            actuator.start();
            var first=actuator.prepare(outcome("a",1,from,period,2,1)).block(Duration.ofSeconds(3));
            assertThat(first.status()).withFailMessage(first.reason()).isEqualTo(PlanActivation.Status.PREPARED);
            now.set(from);assertThat(actuator.activePlan()).isPresent();
            var second=actuator.prepare(outcome("a",2,from.plus(period),period,2,1)).block(Duration.ofSeconds(3));
            assertThat(second.status()).withFailMessage(second.reason()).isEqualTo(PlanActivation.Status.PREPARED);
        }
    }
    @Test void completedPreparationAllowsTheNextEpochFromItsResultSubscriber() {
        var now = new AtomicReference<>(Instant.parse("2026-10-04T10:00:00Z"));
        var from = now.get().plusSeconds(5);
        try (var actuator = new ReplicaPlanActuator(control(3, 2), () -> List.of(registered("f", 1)),
                peers("a"), now::get, () -> true, Duration.ofSeconds(2), Duration.ofSeconds(1))) {
            actuator.start();
            var second = actuator.prepare(outcome("a", 1, from, 2, 1)).flatMap(first -> {
                assertThat(first.status()).withFailMessage(first.reason()).isEqualTo(PlanActivation.Status.PREPARED);
                now.set(from);
                assertThat(actuator.activePlan()).isPresent();
                return actuator.prepare(outcome("a", 2, from.plusSeconds(300), 2, 1));
            }).block(Duration.ofSeconds(3));
            assertThat(second.status()).withFailMessage(second.reason()).isEqualTo(PlanActivation.Status.PREPARED);
        }
    }
    static RegisteredFunction registered(String name,int memory) {
        var cc=new ConcurrencyControlConfig(ConcurrencyControlMode.STATIC_PER_POD,1,1,1,0L,0L,.5,.15,null,null);
        var spec=new FunctionSpec(name,"image",List.of(),Map.of("NANOFAAS_ONE_SHOT_PROFILE","true","NANOFAAS_MAX_CONCURRENT_HANDLERS","1"),new ResourceSpec(null,new ResourceQuantity(java.math.BigDecimal.ONE,memory)),30000,10,100,0,null,ExecutionMode.DEPLOYMENT,RuntimeMode.HTTP,null,new ScalingConfig(ScalingStrategy.NONE,0,10,List.of(),cc));
        return new RegisteredFunction(spec,new DeploymentMetadata(ExecutionMode.DEPLOYMENT,ExecutionMode.DEPLOYMENT,"local",null).withDesiredReplicas(0));
    }
    static EpochOutcome outcome(String node,long epoch,Instant from,int capacity,double load) {
        return outcome(node,epoch,from,Duration.ofSeconds(300),capacity,load);
    }
    static EpochOutcome outcome(String node,long epoch,Instant from,Duration period,int capacity,double load) {
        var problem=new LocalProblem(LocalProblem.Model.LSP,capacity,List.of(new LocalProblem.Function("f",load,1,1,1,1,.9,.1,0,0,0,0)));
        var initial=new LocalReplicaSolver().solve(problem,SolveLimits.forDuration(Duration.ofSeconds(1)));
        var identities=Map.of("f",new AuctionSnapshot.FunctionIdentity("v",3));
        var snapshot=AuctionSnapshot.open(node,node+"-run",epoch,0,1,from,from.plus(period),problem,initial,identities,Map.of());
        var ledger=new SellerLedger(snapshot,OneShotAuctionEngine.Options.base());snapshot=ledger.finalizeAuction().state();
        var input=new EpochInput(problem,identities,Map.of("f",new ForecastSnapshot(new ForecastQuery(node,"f",3,from,from.plus(period)),ForecastSnapshot.Status.AVAILABLE,load,1,"oracle",from.minusSeconds(5))),1);
        return new EpochOutcome(epoch,EpochOutcome.Status.FIXED_ROUNDS_COMPLETED,snapshot,100,10,1,"test",input);
    }
    static PeerTransport peers(String node) {
        return new PeerTransport() {
            public Optional<PeerEndpoint> localEndpoint() { return Optional.of(new PeerEndpoint(node,node+"-run",URI.create("http://"+node+":8080"))); }
            public List<PeerEndpoint> activeNeighbors() { return List.of(); }
            public Mono<byte[]> request(String peer,String topic,byte[] bytes,Duration timeout) { return Mono.error(new IllegalStateException("no neighbor")); }
            public PeerSubscription subscribe(String topic,PeerReceiver receiver) { return ()->{}; }
        };
    }
    @Test void desiredThreeReadyOneRoutesOneAndCloudGetsResidual() {
        var now=new AtomicReference<>(Instant.parse("2026-10-04T10:00:00Z"));var from=now.get().plusSeconds(5);
        var control=mock(ManagedReplicaControl.class);when(control.setReadyConcurrency(any(),anyInt())).thenReturn(true);var desired=new AtomicInteger();
        var lease=new ReplicaControlLease(new FunctionGeneration("f",3),"token","owner",System.nanoTime()+Duration.ofHours(1).toNanos());
        when(control.acquireReplicaLease(any(),anyString(),any())).thenReturn(Optional.of(lease));when(control.ownsReplicaLease(lease)).thenReturn(true);
        when(control.generationOf(any())).thenReturn(lease.generation());when(control.supportsPhysicalReplicaControl(any())).thenReturn(true);
        when(control.setReplicas(any(ReplicaControlLease.class),any(),anyInt())).thenAnswer(a->{desired.set(a.getArgument(2));return true;});
        when(control.observeReplicaStatus(any())).thenAnswer(a->ReplicaObservation.fresh(new ReplicaStatus(desired.get(),Math.min(desired.get(),1)),now.get()));
        try(var actuator=new ReplicaPlanActuator(control,()->List.of(registered("f",1)),peers("a"),now::get,()->true,Duration.ofSeconds(2),Duration.ofSeconds(1))) {
            actuator.start();var result=actuator.prepare(outcome("a",1,from,3,3)).block(Duration.ofSeconds(2));
            assertThat(result.status()).withFailMessage(result.reason()).isEqualTo(PlanActivation.Status.DEGRADED);
            assertThat(result.readyReplicas()).containsEntry("f",1);assertThat(result.plan().functions().get("f").localRate()).isEqualTo(1);
            assertThat(result.plan().functions().get("f").cloudRate()).isEqualTo(2);assertThat(actuator.activePlan()).isEmpty();
            now.set(from);assertThat(actuator.activePlan()).isPresent();
            when(control.generationOf(any())).thenReturn(new FunctionGeneration("f",4));assertThat(actuator.activePlan()).isEmpty();
        }
    }    static final class Wire implements PeerTransport {
        final String node;final Map<String,Wire> network; final Map<String,PeerReceiver> topics=new java.util.concurrent.ConcurrentHashMap<>();
        Wire(String node,Map<String,Wire> network) { this.node=node;this.network=network;network.put(node,this); }
        public Optional<PeerEndpoint> localEndpoint() { return Optional.of(new PeerEndpoint(node,node+"-run",URI.create("http://"+node+":8080"))); }
        public List<PeerEndpoint> activeNeighbors() { return network.values().stream().filter(w->w!=this).map(w->w.localEndpoint().orElseThrow()).toList(); }
        public Mono<byte[]> request(String peer,String topic,byte[] bytes,Duration timeout) { return Mono.defer(()->{var receiver=network.get(peer).topics.get(topic);return receiver==null?Mono.just(new byte[]{0}):receiver.onMessage(node,bytes);}); }
        public PeerSubscription subscribe(String topic,PeerReceiver receiver) { topics.put(topic,receiver);return ()->topics.remove(topic,receiver); }
    }
    static ManagedReplicaControl control(long generation,int readyMaximum) {
        var control=mock(ManagedReplicaControl.class);when(control.setReadyConcurrency(any(),anyInt())).thenReturn(true);var desired=new AtomicInteger();
        var lease=new ReplicaControlLease(new FunctionGeneration("f",generation),"token","owner",System.nanoTime()+Duration.ofHours(1).toNanos());
        when(control.renewReplicaLease(any(),any())).thenAnswer(a->Optional.of(a.getArgument(0)));
        when(control.acquireReplicaLease(any(),anyString(),any())).thenReturn(Optional.of(lease));when(control.ownsReplicaLease(lease)).thenReturn(true);
        when(control.generationOf(any())).thenReturn(lease.generation());when(control.supportsPhysicalReplicaControl(any())).thenReturn(true);
        when(control.setReplicas(any(ReplicaControlLease.class),any(),anyInt())).thenAnswer(a->{desired.set(a.getArgument(2));return true;});
        when(control.observeReplicaStatus(any())).thenAnswer(a->ReplicaObservation.fresh(new ReplicaStatus(desired.get(),Math.min(desired.get(),readyMaximum)),Instant.now()));return control;
    }
    static EpochOutcome assignedOutcome(String node,Instant from,Assignment grant) {
        var base=outcome(node,1,from,2,node.equals("a")?7:0);var state=base.snapshot();long generation=node.equals("a")?3:7;
        var identities=Map.of("f",new AuctionSnapshot.FunctionIdentity("v",generation));
        var snapshot=new AuctionSnapshot(state.nodeId(),state.incarnation(),state.epoch(),state.round(),state.revision(),state.validFrom(),state.validUntil(),state.baseProblem(),identities,
            Map.of(node.equals("a")?"b":"a",node.equals("a")?"b-run":"a-run"),Map.of("f",2),state.prices(),Map.of(),List.of(),Map.of(grant.id(),grant),Set.of(),true,"FINALIZED");
        var forecast=new ForecastSnapshot(new ForecastQuery(node,"f",generation,from,from.plusSeconds(300)),ForecastSnapshot.Status.AVAILABLE,node.equals("a")?7.0:0.0,1,"oracle",from.minusSeconds(5));
        var input=new EpochInput(base.input().problem(),identities,Map.of("f",forecast),1);
        return new EpochOutcome(1,EpochOutcome.Status.FIXED_ROUNDS_COMPLETED,snapshot,100,10,1,"test",input);
    }
    @Test void readinessShrinksGrantAndBuyerUsesOnlyAcknowledgedQuantity() {
        var now=new AtomicReference<>(Instant.parse("2026-10-04T10:00:00Z"));var from=now.get().plusSeconds(10);
        var network=new java.util.concurrent.ConcurrentHashMap<String,Wire>();var a=new Wire("a",network);var b=new Wire("b",network);
        var grant=new Assignment("grant-1","a","a-run","b","b-run","f","v",7,3,1,2,false);
        try(var buyer=new ReplicaPlanActuator(control(3,2),()->List.of(registered("f",1)),a,now::get,()->true,Duration.ofSeconds(2),Duration.ofSeconds(1));
            var seller=new ReplicaPlanActuator(control(7,1),()->List.of(registered("f",1)),b,now::get,()->true,Duration.ofSeconds(2),Duration.ofSeconds(1))) {
            buyer.start();seller.start();
            var results=Mono.zip(buyer.prepare(assignedOutcome("a",from,grant)),seller.prepare(assignedOutcome("b",from,grant))).block(Duration.ofSeconds(3));
            assertThat(results.getT1().status()).withFailMessage(results.getT1().reason()).isEqualTo(PlanActivation.Status.DEGRADED);
            var bp=results.getT1().plan().functions().get("f");assertThat(bp.outbound()).hasSize(1);assertThat(bp.outbound().getFirst().quantity()).isEqualTo(1);assertThat(bp.cloudRate()).isEqualTo(4);
            assertThat(results.getT2().plan().functions().get("f").inbound().getFirst().quantity()).isEqualTo(1);
            assertThat(seller.drainAndRelease().block()).isFalse();
        }
    }
    static EpochOutcome memoryOutcome(long epoch,Instant from,double fLoad,double gLoad) {
        var rows=List.of(new LocalProblem.Function("f",fLoad,1,1,1,1,.9,.1,0,0,0,0),new LocalProblem.Function("g",gLoad,1,1,1,1,.9,.1,0,0,0,0));
        var problem=new LocalProblem(LocalProblem.Model.LSP,2,rows);var initial=new LocalReplicaSolver().solve(problem,SolveLimits.forDuration(Duration.ofSeconds(1)));
        var identities=Map.of("f",new AuctionSnapshot.FunctionIdentity("v",3),"g",new AuctionSnapshot.FunctionIdentity("v",3));
        var snapshot=new SellerLedger(AuctionSnapshot.open("a","a-run",epoch,0,1,from,from.plusSeconds(300),problem,initial,identities,Map.of()),OneShotAuctionEngine.Options.base()).finalizeAuction().state();
        var forecasts=new HashMap<String,ForecastSnapshot>();rows.forEach(row->forecasts.put(row.id(),new ForecastSnapshot(new ForecastQuery("a",row.id(),3,from,from.plusSeconds(300)),ForecastSnapshot.Status.AVAILABLE,row.load(),1,"oracle",from.minusSeconds(5))));
        return new EpochOutcome(epoch,EpochOutcome.Status.FIXED_ROUNDS_COMPLETED,snapshot,100,10,1,"test",new EpochInput(problem,identities,forecasts,1));
    }
}
