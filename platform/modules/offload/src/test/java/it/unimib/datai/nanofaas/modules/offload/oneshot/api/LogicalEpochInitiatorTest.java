package it.unimib.datai.nanofaas.modules.offload.oneshot.api;

import it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.EpochTiming;
import it.unimib.datai.nanofaas.p2papi.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

class LogicalEpochInitiatorTest {
    record Started(long epoch,boolean catchUp) {}
    static final class Node implements PeerTransport,AutoCloseable {
        final String id;
        final Map<String,Node> network;
        final List<String> neighbors;
        final AtomicLong nanos;
        final List<Started> started=new CopyOnWriteArrayList<>();
        final List<Long> activated=new CopyOnWriteArrayList<>();
        final Map<String,PeerReceiver> receivers=new ConcurrentHashMap<>();
        final LogicalEpochInitiator initiator;
        boolean dropFirst;
        Node(String id,Map<String,Node> network,List<String> neighbors,long offset) {
            this.id=id;this.network=network;this.neighbors=neighbors;nanos=new AtomicLong(offset);network.put(id,this);
            initiator=new LogicalEpochInitiator(this,nanos::get,new LogicalEpochInitiator.Work() {
                public Mono<Boolean> prepare(long epoch,EpochTiming timing,boolean catchUp) {
                    return Mono.fromSupplier(()->{started.add(new Started(epoch,catchUp));return true;});
                }
                public Mono<Void> activate(String sessionId,long epoch) {
                    return Mono.fromRunnable(()->activated.add(epoch));
                }
            });
            initiator.start();
        }
        void arm(long epoch) { initiator.arm(LogicalOperationsTest.logical(OneShotConfigurationTest.config(),true),epoch,Duration.ofSeconds(20)); }
        PeerEndpoint endpoint() { return new PeerEndpoint(id,id+"-run",URI.create("http://"+id+":8080")); }
        public Optional<PeerEndpoint> localEndpoint() { return Optional.of(endpoint()); }
        public List<PeerEndpoint> activeNeighbors() { return neighbors.stream().map(network::get).filter(Objects::nonNull).map(Node::endpoint).toList(); }
        public PeerSubscription subscribe(String topic,PeerReceiver receiver) { receivers.put(topic,receiver);return ()->receivers.remove(topic,receiver); }
        public Mono<byte[]> request(String peer,String topic,byte[] payload,Duration timeout) {
            return Mono.defer(()-> {
                if(dropFirst) {dropFirst=false;return Mono.error(new IllegalStateException("lost request"));}
                var receiver=network.get(peer).receivers.get(topic);
                if(receiver==null) return Mono.error(new IllegalStateException("offline"));
                return receiver.onMessage(id,payload).flatMap(reply->receiver.onMessage(id,payload));
            });
        }
        @Override public void close() { initiator.stop(); }
    }

    @Test void oneMonotonicTriggerPropagatesOnceAcrossTwoTrianglesDespiteDuplicateAndLostMessages() throws Exception {
        Map<String,Node> network=new ConcurrentHashMap<>();
        var edges=Map.of("a",List.of("b","c"),"b",List.of("a","c"),"c",List.of("a","b","d","e"),"d",List.of("c","e"),"e",List.of("c","d"));
        try {
            int index=0;
            for(var id:List.of("a","b","c","d","e")) new Node(id,network,edges.get(id),Long.MAX_VALUE-Duration.ofSeconds(++index).toNanos());
            network.values().forEach(n->n.arm(0));network.get("a").dropFirst=true;
            network.get("a").initiator.tick();
            await(()->network.values().stream().allMatch(n->n.started.size()==1));
            assertThat(network.values()).allSatisfy(n->assertThat(n.started).containsExactly(new Started(0,false)));
            network.values().forEach(n->{n.nanos.addAndGet(Duration.ofSeconds(20).toNanos());n.initiator.tick();});
            await(()->network.values().stream().allMatch(n->n.activated.size()==1));
            network.get("a").nanos.addAndGet(Duration.ofSeconds(280).toNanos());
            network.get("a").initiator.tick();
            await(()->network.values().stream().allMatch(n->n.started.size()==2));
            assertThat(network.values()).allSatisfy(n->assertThat(n.started).containsExactly(new Started(0,false),new Started(1,false)));
        } finally { network.values().forEach(Node::close); }
    }

    @Test void latePeerUsesStatusBeforeRequestingCatchUpAndStopDisarmsActivation() throws Exception {
        Map<String,Node> network=new ConcurrentHashMap<>();
        try(var a=new Node("a",network,List.of("b"),100);var b=new Node("b",network,List.of("a"),-100)) {
            a.arm(2);a.initiator.tick();await(()->a.started.size()==1);
            b.arm(0);
            // A later tick retransmits status/proposal only within its bounded initiation attempt.
            var settings=LogicalOperationsTest.logical(OneShotConfigurationTest.config(),true);
            var from=settings.anchor().plus(settings.period().multipliedBy(2));
            var proposal=new LogicalEpochInitiator.Message(2,"PROPOSE","session","a","a-run",2,from,from.plus(settings.period()),Duration.ofSeconds(20));
            var bytes=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(proposal);
            a.request("b",LogicalEpochInitiator.TOPIC,bytes,Duration.ofSeconds(1)).block();
            await(()->b.started.size()==1);
            assertThat(b.started).containsExactly(new Started(2,true));
            b.initiator.stop();b.nanos.addAndGet(Duration.ofSeconds(100).toNanos());b.initiator.tick();
            assertThat(b.activated).isEmpty();
        }
    }

    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        while(!condition.getAsBoolean() && System.nanoTime()-deadline<0) Thread.sleep(10);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test void disarmingAndRearmingKeepsTheCurrentPeerSubscriptionUsable() throws Exception {
        Map<String,Node> network=new ConcurrentHashMap<>();
        try(var a=new Node("a",network,List.of("b"),0);var b=new Node("b",network,List.of("a"),0)) {
            a.arm(0);b.arm(0);a.initiator.disarm();b.initiator.disarm();
            a.arm(1);b.arm(1);a.initiator.tick();
            await(()->b.started.size()==1);
            assertThat(b.started).containsExactly(new Started(1,false));
        }
    }

    @Test void proposalTraversesALineWithoutAnIndependentTickAtTheLastNode() throws Exception {
        Map<String,Node> network=new ConcurrentHashMap<>();
        try(var a=new Node("a",network,List.of("b"),0);var b=new Node("b",network,List.of("a","c"),-100);var c=new Node("c",network,List.of("b"),100)) {
            network.values().forEach(n->n.arm(0));a.initiator.tick();
            await(()->network.values().stream().allMatch(n->n.started.size()==1));
            assertThat(c.started).containsExactly(new Started(0,false));
        }
    }

    @Test void wrongSessionsIncarnationsLabelsAndRetiredSubscriptionsCannotStartWork() throws Exception {
        Map<String,Node> network=new ConcurrentHashMap<>();
        try(var a=new Node("a",network,List.of("b"),0);var b=new Node("b",network,List.of("a"),0)) {
            a.arm(0);b.arm(0);
            var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
            var settings=LogicalOperationsTest.logical(OneShotConfigurationTest.config(),true);
            var from=settings.anchor();var until=from.plus(settings.period());
            for(var message:List.of(
                    new LogicalEpochInitiator.Message(2,"PROPOSE","wrong","a","a-run",0,from,until,Duration.ofSeconds(20)),
                    new LogicalEpochInitiator.Message(2,"PROPOSE","session","a","old",0,from,until,Duration.ofSeconds(20)),
                    new LogicalEpochInitiator.Message(2,"PROPOSE","session","a","a-run",0,from.plusSeconds(1),until.plusSeconds(1),Duration.ofSeconds(20)),
                    new LogicalEpochInitiator.Message(1,"PROPOSE","session","a","a-run",0,from,until,Duration.ofSeconds(20)))) {
                assertThat(a.request("b",LogicalEpochInitiator.TOPIC,mapper.writeValueAsBytes(message),Duration.ofSeconds(1)).block()).containsExactly((byte)0);
            }
            var old=b.receivers.get(LogicalEpochInitiator.TOPIC);
            b.initiator.stop();b.initiator.start();b.arm(1);
            var message=new LogicalEpochInitiator.Message(2,"PROPOSE","session","a","a-run",1,until,until.plus(settings.period()),Duration.ofSeconds(20));
            assertThat(old.onMessage("a",mapper.writeValueAsBytes(message)).block()).containsExactly((byte)0);
            assertThat(b.started).isEmpty();
        }
    }
}
