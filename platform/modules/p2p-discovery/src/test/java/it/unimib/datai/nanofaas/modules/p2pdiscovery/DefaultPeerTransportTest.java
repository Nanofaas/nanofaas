package it.unimib.datai.nanofaas.modules.p2pdiscovery;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
class DefaultPeerTransportTest {
    final PeerMessagingTest.FakeWire wire = new PeerMessagingTest.FakeWire();
    final PeerTable table = new PeerTable(() -> new NeighborSelector.Settings(null, null));
    final PeerMessaging messaging = new PeerMessaging(wire, table);
    final AtomicReference<DefaultPeerTransport.Session> session = new AtomicReference<>(new DefaultPeerTransport.Session("self", "run1", messaging));
    final DefaultPeerTransport transport = new DefaultPeerTransport(table, session::get, URI.create("http://self:8080"), 4);
    @Test void completingRequestsAdmitsEveryPeerBeyondTheConcurrencyLimit() {
        var pending = new java.util.LinkedHashMap<String, reactor.core.publisher.Sinks.One<byte[]>>();
        PeerMessaging.Wire asynchronous = new PeerMessaging.Wire() {
            public Mono<Void> send(String address, String topic, byte[] payload) { return Mono.empty(); }
            public Mono<byte[]> request(String address, String topic, byte[] payload, Duration timeout) {
                var response = reactor.core.publisher.Sinks.<byte[]>one();
                pending.put(address, response);
                return response.asMono();
            }
            public void handle(String topic, PeerCluster.Handler handler) {}
        };
        for (int i = 0; i < 8; i++) table.upsert("peer-" + i, "peer-" + i + ":7000");
        var current = new DefaultPeerTransport.Session("self", "run", new PeerMessaging(asynchronous, table));
        var adapter = new DefaultPeerTransport(table, () -> current, null, 4);
        var result = reactor.core.publisher.Flux.range(0, 8)
                .flatMap(i -> adapter.request("peer-" + i, "one-shot", new byte[0], Duration.ofSeconds(5)), 4)
                .collectList().toFuture();
        assertThat(pending).hasSize(4);
        while (!result.isDone()) {
            var responses = java.util.List.copyOf(pending.values());
            responses.forEach(response -> response.tryEmitValue(new byte[]{1}));
        }
        assertThat(result.join()).hasSize(8);
        assertThat(pending).hasSize(8);
    }
    static final class StrictWire implements PeerMessaging.Wire {
        final java.util.Map<String,PeerCluster.Handler> handlers=new java.util.HashMap<>();
        public Mono<Void> send(String a,String t,byte[] p) {return Mono.empty();}
        public Mono<byte[]> request(String a,String t,byte[] p,Duration d) {return Mono.empty();}
        public void handle(String topic,PeerCluster.Handler handler) {if(handlers.putIfAbsent(topic,handler)!=null) throw new IllegalStateException("handler already registered");}
        public void unhandle(String topic,PeerCluster.Handler handler) {handlers.remove(topic,handler);}
    }
    @Test void subscriptionBeforeFirstPollBindsEachPhysicalTopicOnlyOnce() {
        var strict=new StrictWire();var session=new DefaultPeerTransport.Session("self","run",new PeerMessaging(strict,table));
        var adapter=new DefaultPeerTransport(table,()->session,null,1);
        adapter.subscribe("one-shot",(sender,payload)->Mono.just(payload));
        assertThatCode(adapter::refresh).doesNotThrowAnyException();
        assertThat(strict.handlers).containsOnlyKeys(DefaultPeerTransport.ANNOUNCEMENT_TOPIC,"one-shot");
        adapter.stop();
    }
    @Test void closeAndResubscribeRemoveTheExactPhysicalHandler() {
        table.upsert("a","a:7000");var strict=new StrictWire();var session=new DefaultPeerTransport.Session("self","run",new PeerMessaging(strict,table));
        var adapter=new DefaultPeerTransport(table,()->session,null,1);adapter.refresh();
        var first=adapter.subscribe("one-shot",(sender,payload)->Mono.just(new byte[]{1}));var old=strict.handlers.get("one-shot");first.close();
        assertThat(strict.handlers).doesNotContainKey("one-shot");
        adapter.subscribe("one-shot",(sender,payload)->Mono.just(new byte[]{2}));
        assertThat(strict.handlers.get("one-shot").onMessage("a:7000",new byte[0]).block()).containsExactly((byte)2);
        assertThatThrownBy(()->old.onMessage("a:7000",new byte[0]).block()).isInstanceOf(PeerCluster.Dropped.class);
        adapter.stop();assertThat(strict.handlers).isEmpty();
    }
    @Test void onlyCurrentActiveExplicitAnnouncementsAreEligible() {
        table.upsert("a", "a:7000"); table.upsert("legacy", "legacy:7000");
        transport.refresh();
        transport.acceptAnnouncement("a", table.activationGeneration("a"), DefaultPeerTransport.encodeAnnouncement("a", "a-run", URI.create("http://a:8080")));
        assertThat(transport.activeNeighbors()).extracting(e -> e.peerId()).containsExactly("a");
        table.setApiMode("a", PeerMode.EXCLUDED);
        assertThat(transport.activeNeighbors()).isEmpty();
        table.setApiMode("a", PeerMode.AUTO);
        assertThat(transport.activeNeighbors()).isEmpty();
    }
    @Test void closedOrOldIncarnationSubscriptionsNeverDeliver() {
        table.upsert("a", "a:7000"); transport.refresh();
        AtomicInteger calls = new AtomicInteger();
        var sub = transport.subscribe("one-shot", (sender, payload) -> { calls.incrementAndGet(); return Mono.just(payload); });
        var old = wire.handlers.get("one-shot");
        old.onMessage("a:7000", new byte[0]).block();
        sub.close();
        assertThatThrownBy(() -> old.onMessage("a:7000", new byte[0]).block()).isInstanceOf(PeerCluster.Dropped.class);
        transport.subscribe("one-shot", (sender, payload) -> { calls.incrementAndGet(); return Mono.just(payload); });
        var previous = wire.handlers.get("one-shot");
        session.set(new DefaultPeerTransport.Session("self", "run2", messaging));
        assertThatThrownBy(() -> previous.onMessage("a:7000", new byte[0]).block()).isInstanceOf(PeerCluster.Dropped.class);
        assertThat(calls.get()).isEqualTo(1);
        session.set(null);
        assertThatThrownBy(() -> transport.request("a", "one-shot", new byte[0], Duration.ofSeconds(1)).block()).isInstanceOf(IllegalStateException.class);
    }
    @Test void oversizedPayloadAndConcurrentRequestsAreBounded() {
        table.upsert("a", "a:7000"); transport.refresh();
        assertThatThrownBy(() -> transport.request("a", "one-shot", new byte[1048577], Duration.ofSeconds(1)).block()).isInstanceOf(IllegalArgumentException.class);
        transport.subscribe("one-shot", (sender, payload) -> Mono.just(payload));
        assertThatThrownBy(() -> wire.handlers.get("one-shot").onMessage("a:7000", new byte[1048577]).block()).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void limitsOutstandingRequestsAndReleasesPermitOnCancellation() {
        PeerMessaging.Wire slowWire = new PeerMessaging.Wire() {
            public Mono<Void> send(String a, String t, byte[] p) { return Mono.empty(); }
            public Mono<byte[]> request(String a, String t, byte[] p, Duration d) { return Mono.never(); }
            public void handle(String t, PeerCluster.Handler h) {}
        };
        table.upsert("a", "a:7000");
        var slowSession = new DefaultPeerTransport.Session("self", "run", new PeerMessaging(slowWire, table));
        var limited = new DefaultPeerTransport(table, () -> slowSession, null, 1);
        var first = limited.request("a", "one-shot", new byte[0], Duration.ofSeconds(5)).subscribe();
        try {
            assertThatThrownBy(() -> limited.request("a", "one-shot", new byte[0], Duration.ofSeconds(1)).block())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("limit");
        } finally { first.dispose(); }
        var second = limited.request("a", "one-shot", new byte[0], Duration.ofSeconds(5)).subscribe();
        second.dispose();
    }
    @Test void stopDropsRetainedWireHandlers() {
        table.upsert("a", "a:7000"); transport.refresh();
        AtomicInteger calls = new AtomicInteger();
        transport.subscribe("one-shot", (sender, payload) -> { calls.incrementAndGet(); return Mono.just(payload); });
        var handler = wire.handlers.get("one-shot");
        transport.stop();
        assertThatThrownBy(() -> handler.onMessage("a:7000", new byte[0]).block()).isInstanceOf(PeerCluster.Dropped.class);
        assertThat(calls.get()).isZero();
        assertThatThrownBy(() -> transport.request("a", "one-shot", new byte[0], Duration.ofSeconds(1)).block()).isInstanceOf(IllegalStateException.class);
    }    @Test void localEndpointUsesCurrentTransportIncarnationAndDisappearsOnStop() {
        assertThat(transport.localEndpoint().orElseThrow().incarnation()).isEqualTo("run1");
        session.set(new DefaultPeerTransport.Session("self","run2",messaging));
        assertThat(transport.localEndpoint().orElseThrow().incarnation()).isEqualTo("run2");
        transport.stop(); assertThat(transport.localEndpoint()).isEmpty();
    }

}
