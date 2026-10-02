package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import it.unimib.datai.nanofaas.modules.p2pdiscovery.NeighborSelector.Settings;
import it.unimib.datai.nanofaas.modules.p2pdiscovery.PeerMessaging.PeerNotActiveException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeerMessagingTest {
    /** Records traffic and lets the test inject incoming messages. */
    static final class FakeWire implements PeerMessaging.Wire {
        final List<String> sent = new ArrayList<>();
        final Map<String, PeerCluster.Handler> handlers = new HashMap<>();

        public Mono<Void> send(String a, String t, byte[] p) { sent.add(a + "|" + t); return Mono.empty(); }
        public Mono<byte[]> request(String a, String t, byte[] p, Duration d) { sent.add(a + "|" + t); return Mono.just(new byte[]{1}); }
        public void handle(String t, PeerCluster.Handler h) { handlers.put(t, h); }
    }

    private final FakeWire wire = new FakeWire();
    private final PeerTable table = new PeerTable(() -> new Settings(null, null));
    private final PeerMessaging messaging = new PeerMessaging(wire, table);

    @Test
    void sendGoesToTheAddressOfAnActivePeer() {
        table.upsert("a", "10.0.0.1:1");
        messaging.send("a", "t", new byte[0]).block();
        assertThat(wire.sent).containsExactly("10.0.0.1:1|t");
    }

    @Test
    void sendToInactiveOrUnknownPeerFailsExplicitly() {
        table.upsert("x", "10.0.0.9:1");
        table.setApiMode("x", PeerMode.EXCLUDED);
        Mono<Void> toExcluded = messaging.send("x", "t", new byte[0]);
        Mono<byte[]> toUnknown = messaging.request("nobody", "t", new byte[0], Duration.ofSeconds(1));
        assertThatThrownBy(toExcluded::block).isInstanceOf(PeerNotActiveException.class);
        assertThatThrownBy(toUnknown::block).isInstanceOf(PeerNotActiveException.class);
        assertThat(wire.sent).isEmpty();
    }

    @Test
    void broadcastReachesOnlyActivePeers() {
        table.upsert("a", "a:1");
        table.upsert("b", "b:1");
        table.setApiMode("b", PeerMode.EXCLUDED);
        assertThat(messaging.broadcast("t", new byte[0]).block()).isEqualTo(1);
        assertThat(wire.sent).containsExactly("a:1|t");
    }

    @Test
    void incomingFromActivePeerIsDeliveredWithItsId() {
        table.upsert("a", "a:1");
        List<String> got = new ArrayList<>();
        messaging.subscribe("t", (sender, p) -> { got.add(sender); return Mono.just(new byte[]{9}); });
        byte[] reply = wire.handlers.get("t").onMessage("a:1", new byte[0]).block();
        assertThat(got).containsExactly("a");
        assertThat(reply).containsExactly(9);
    }

    @Test
    void incomingFromInactiveOrUnknownSenderIsDropped() {
        table.upsert("b", "b:1");
        table.setApiMode("b", PeerMode.EXCLUDED);
        List<String> got = new ArrayList<>();
        messaging.subscribe("t", (s, p) -> { got.add(s); return Mono.empty(); });
        Mono<byte[]> fromExcluded = wire.handlers.get("t").onMessage("b:1", new byte[0]);
        Mono<byte[]> fromStranger = wire.handlers.get("t").onMessage("stranger:1", new byte[0]);
        // an error, not an empty Mono: PeerCluster answers an empty Mono to a request with an empty reply
        assertThatThrownBy(fromExcluded::block).isInstanceOf(PeerCluster.Dropped.class);
        assertThatThrownBy(fromStranger::block).isInstanceOf(PeerCluster.Dropped.class);
        assertThat(got).isEmpty();
    }

    @Test
    void reservedTopicPrefixIsRejected() {
        assertThatThrownBy(() -> messaging.subscribe("p2p.ping", (s, p) -> Mono.empty())).isInstanceOf(IllegalArgumentException.class);
        Mono<Void> send = messaging.send("a", "p2p.x", new byte[0]);          // an error signal, not a throw while building the Mono
        Mono<Integer> broadcast = messaging.broadcast("p2p.x", new byte[0]);
        assertThatThrownBy(send::block).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(broadcast::block).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void broadcastTargetsAreChosenWhenSubscribedNotWhenBuilt() {
        Mono<Integer> broadcast = messaging.broadcast("t", new byte[0]);
        table.upsert("late", "l:1");                      // becomes active after the Mono was built
        assertThat(broadcast.block()).isEqualTo(1);
        assertThat(wire.sent).containsExactly("l:1|t");
    }
}
