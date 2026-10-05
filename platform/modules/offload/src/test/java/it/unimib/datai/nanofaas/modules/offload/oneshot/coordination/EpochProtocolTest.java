package it.unimib.datai.nanofaas.modules.offload.oneshot.coordination;
import it.unimib.datai.nanofaas.p2papi.*;
import it.unimib.datai.nanofaas.forecastingapi.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.auction.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.solver.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.assertj.core.api.Assertions.*;
class EpochProtocolTest {
    static final class Fake implements PeerTransport {
        final String id; final Map<String,Fake> network; volatile String incarnation; volatile PeerReceiver receiver;
        boolean duplicate=true, loseGrants;
        Fake(String id,Map<String,Fake> network) { this.id=id;this.network=network;incarnation=id+"-run";network.put(id,this); }
        PeerEndpoint endpoint() { return new PeerEndpoint(id,incarnation,URI.create("http://"+id+":8080")); }
        public Optional<PeerEndpoint> localEndpoint() { return Optional.of(endpoint()); }
        public List<PeerEndpoint> activeNeighbors() { return network.values().stream().filter(n->n!=this).map(Fake::endpoint).toList(); }
        public Mono<byte[]> request(String peer,String topic,byte[] payload,Duration timeout) {
            return Mono.defer(()-> {
                var target=network.get(peer); if(target.receiver==null) return Mono.just(new byte[]{0});
                if(loseGrants && new AuctionCodec().decode(payload).phase()==AuctionCodec.Phase.GRANTS) return Mono.error(new IllegalStateException("grant lost"));
                return target.receiver.onMessage(id,payload).flatMap(reply->duplicate?target.receiver.onMessage(id,payload):Mono.just(reply));
            });
        }
        public PeerSubscription subscribe(String topic,PeerReceiver receiver) { this.receiver=receiver;return ()->this.receiver=null; }
    }
    static EpochInput input(String node,long epoch,Instant from,Instant until) {
        double load=node.equals("a")?7:0;
        return new EpochInput(new LocalProblem(LocalProblem.Model.LSP,2,List.of(new LocalProblem.Function("f",load,1,1,1,1,.9,.1,0,0,0,0))),
            Map.of("f",new AuctionSnapshot.FunctionIdentity("image-v1",node.equals("a")?3:7)),
            Map.of("f",new ForecastSnapshot(new ForecastQuery(node,"f",node.equals("a")?3:7,from,until),ForecastSnapshot.Status.AVAILABLE,load,1,"oracle",Instant.now())),1);
    }
    static EpochSettings settings(int rounds) { return new EpochSettings(Duration.ofSeconds(3),Duration.ofMillis(200),Duration.ofSeconds(1),rounds,2,16,4,.1); }
    static ClockHealth health() { var health=new ClockHealth(Duration.ofMillis(100),Duration.ofMinutes(1),Instant::now);health.sample(Duration.ZERO,Instant.now());return health; }
    @Test void threeNodesConvergeWithDuplicateBatchesAndFrozenForecasts() {
        var network=new ConcurrentHashMap<String,Fake>(); var transports=List.of(new Fake("a",network),new Fake("b",network),new Fake("c",network));
        var nodes=transports.stream().map(t->new EpochCoordinator(t,(epoch,from,until)->input(t.id,epoch,from,until),settings(10),health())).toList();
        nodes.forEach(EpochCoordinator::start);
        try {
            var from=Instant.now().plusSeconds(10);var until=from.plusSeconds(300);
            var results=Mono.zip(nodes.stream().map(n->n.prepare(1,from,until)).toList(),objects->Arrays.stream(objects).map(o->(EpochOutcome)o).toList()).block(Duration.ofSeconds(5));
            assertThat(results).allSatisfy(r->{ assertThat(r.status()).withFailMessage(r.reason()).isEqualTo(EpochOutcome.Status.CONVERGED);assertThat(r.censored()).isFalse();assertThat(r.auctionNanos()).isGreaterThanOrEqualTo(r.solverNanos()); });
            assertThat(results.getFirst().snapshot().assignments().values().stream().mapToLong(Assignment::quantity).sum()).isEqualTo(4);
            assertThat(nodes.getFirst().prepare(1,from,until).block().status()).isEqualTo(EpochOutcome.Status.FAILED);
        } finally { nodes.forEach(EpochCoordinator::close); }
    }
    @Test void roundLimitAndGrantLossAreCensoredOutcomes() {
        for(boolean lose:List.of(false,true)) {
            var network=new ConcurrentHashMap<String,Fake>(); var transports=List.of(new Fake("a",network),new Fake("b",network),new Fake("c",network)); transports.forEach(t->t.loseGrants=lose);
            var nodes=transports.stream().map(t->new EpochCoordinator(t,(epoch,from,until)->input(t.id,epoch,from,until),settings(lose?10:1),health())).toList();nodes.forEach(EpochCoordinator::start);
            try {
                var from=Instant.now().plusSeconds(10);
                var results=Mono.zip(nodes.stream().map(n->n.prepare(1,from,from.plusSeconds(300))).toList(),objects->Arrays.stream(objects).map(o->(EpochOutcome)o).toList()).block(Duration.ofSeconds(5));
                assertThat(results).allSatisfy(r->{assertThat(r.censored()).isTrue();assertThat(r.status()).isEqualTo(lose?EpochOutcome.Status.FAILED:EpochOutcome.Status.ROUND_LIMIT);});
            } finally { nodes.forEach(EpochCoordinator::close); }
        }
    }    @Test void virtualEpochStartClockFailureAndRejoinInvalidatePreparation() {
        for(String fault:List.of("deadline","clock","rejoin")) {
            var network=new ConcurrentHashMap<String,Fake>(); var transport=new Fake("a",network);
            var now=new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-10-04T10:00:00Z"));
            var health=new ClockHealth(Duration.ofMillis(100),Duration.ofMinutes(1),now::get); health.sample(Duration.ZERO,now.get());
            var from=now.get().plusSeconds(10);
            try(var node=new EpochCoordinator(transport,(epoch,start,end)-> {
                var input=input("a",epoch,start,end);
                if(fault.equals("deadline")) now.set(from);
                if(fault.equals("clock")) health.sample(Duration.ofSeconds(1),now.get());
                if(fault.equals("rejoin")) transport.incarnation="new-run";
                return input;
            },settings(10),health,now::get,OneShotAuctionEngine.Options.base())) {
                node.start(); var result=node.prepare(1,from,from.plusSeconds(300)).block(Duration.ofSeconds(2));
                assertThat(result.status()).isEqualTo(fault.equals("deadline")?EpochOutcome.Status.DEADLINE:EpochOutcome.Status.FAILED);
                assertThat(result.censored()).isTrue();
            }
        }
    }
    @Test void stopFencesRetainedCallbacksAndNewEpochCanStart() throws Exception {
        var network=new ConcurrentHashMap<String,Fake>(); var transport=new Fake("a",network); new Fake("missing",network);
        var started=new java.util.concurrent.CountDownLatch(1);
        try(var node=new EpochCoordinator(transport,(epoch,from,until)-> { started.countDown(); return input("a",epoch,from,until); },settings(10),health())) {
            node.start(); var receiver=transport.receiver; var from=Instant.now().plusSeconds(10);
            var result=node.prepare(1,from,from.plusSeconds(300)).toFuture(); assertThat(started.await(1,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            node.stop(); assertThat(result.get(1,java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo(EpochOutcome.Status.FAILED);
            var old=new AuctionCodec.Batch(1,"missing","missing-run",1,0,AuctionCodec.Phase.HELLO,from,from.plusSeconds(300),false,List.of());
            assertThat(receiver.onMessage("missing",new AuctionCodec().encode(old)).block()).containsExactly((byte)0);
            network.remove("missing"); node.start();
            assertThat(node.prepare(2,from,from.plusSeconds(300)).block().status()).isEqualTo(EpochOutcome.Status.CONVERGED);
            assertThat(receiver.onMessage("missing",new AuctionCodec().encode(old)).block()).containsExactly((byte)0);
        }
    }

    @Test void sharedFractionalGridConvergesButMismatchedPeerUnitsFailClosed() {
        for(boolean mixed:List.of(false,true)) {
            var network=new ConcurrentHashMap<String,Fake>();
            var transports=List.of(new Fake("a",network),new Fake("b",network));
            var nodes=transports.stream().map(t->new EpochCoordinator(t,(epoch,from,until)-> {
                var base=input(t.id,epoch,from,until);double q=mixed && t.id.equals("a")?1:.5;
                var rows=base.problem().functions().stream().map(f->new LocalProblem.Function(f.id(),f.load()/q,f.demandSeconds()*q,f.utilization(),f.memoryMiB(),f.alpha(),f.delta(),f.gamma(),f.price(),0,0,0)).toList();
                return new EpochInput(new LocalProblem(LocalProblem.Model.LSP,base.problem().memoryCapacityMiB(),rows),base.identities(),base.forecasts(),1,q);
            },settings(10),health())).toList();
            nodes.forEach(EpochCoordinator::start);
            try {
                var from=Instant.now().plusSeconds(10);
                var results=Mono.zip(nodes.stream().map(n->n.prepare(1,from,from.plusSeconds(300))).toList(),objects->Arrays.stream(objects).map(o->(EpochOutcome)o).toList()).block(Duration.ofSeconds(5));
                assertThat(results).allSatisfy(r->{
                    if(mixed) { assertThat(r.status()).isNotEqualTo(EpochOutcome.Status.CONVERGED);assertThat(r.censored()).isTrue(); }
                    else assertThat(r.status()).withFailMessage(r.reason()).isEqualTo(EpochOutcome.Status.CONVERGED);
                });
            } finally { nodes.forEach(EpochCoordinator::close); }
        }
    }
}
