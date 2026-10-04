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
    }
}
