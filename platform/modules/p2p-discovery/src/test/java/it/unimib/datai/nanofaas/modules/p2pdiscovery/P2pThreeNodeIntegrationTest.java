package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class P2pThreeNodeIntegrationTest {
    private final List<P2pService> services = new ArrayList<>();

    @AfterEach
    void stop() {
        services.forEach(P2pService::stop);
    }

    private P2pService node(String id, List<String> seeds, Double maxLatencyMs) {
        P2pProperties p = new P2pProperties(true, id, 0, "127.0.0.1", seeds, null, maxLatencyMs,
                Duration.ofMillis(200), Duration.ofSeconds(1), null, null);
        P2pSettings s = new P2pSettings(p.maxNeighbors(), p.maxLatencyMs());
        P2pService svc = new P2pService(p, new PeerTable(s::effective), s, new SimpleMeterRegistry());
        svc.start();
        services.add(svc);
        return svc;
    }

    @Test
    void discoveryRttAndMessagingWorkEndToEnd() {
        P2pService a = node("a", List.of(), null);
        P2pService b = node("b", List.of(a.address()), null);
        node("c", List.of(a.address()), null);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(a.tableForTest().active()).extracting(PeerTable.Peer::id).containsExactlyInAnyOrder("b", "c");
            assertThat(a.tableForTest().snapshot()).allSatisfy(p -> assertThat(p.rttMs()).isNotNull());
        });

        List<String> got = new CopyOnWriteArrayList<>();
        b.messaging().subscribe("hello", (sender, payload) -> {
            got.add(sender + ":" + new String(payload, StandardCharsets.UTF_8));
            return Mono.just("ack".getBytes(StandardCharsets.UTF_8));
        });
        byte[] reply = a.messaging().request("b", "hello", "hi".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(3))
                .block(Duration.ofSeconds(5));
        assertThat(new String(reply, StandardCharsets.UTF_8)).isEqualTo("ack");
        assertThat(got).containsExactly("a:hi");
        assertThat(a.messaging().broadcast("hello", new byte[0]).block()).isEqualTo(2);
    }

    @Test
    void excludingAPeerStopsTrafficBothWays() {
        P2pService a = node("a", List.of(), null);
        P2pService b = node("b", List.of(a.address()), null);
        await().atMost(Duration.ofSeconds(15)).until(() -> a.tableForTest().isActive("b"));
        b.messaging().subscribe("t", (s, p) -> Mono.just(new byte[]{1}));

        // the receiving side: b has excluded a, so a request from a must fail instead of getting an empty reply
        b.tableForTest().setApiMode("a", PeerMode.EXCLUDED);
        Mono<byte[]> refused = a.messaging().request("b", "t", new byte[0], Duration.ofSeconds(1));
        org.assertj.core.api.Assertions.assertThatThrownBy(refused::block)
                .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);

        a.tableForTest().setApiMode("b", PeerMode.EXCLUDED);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> a.messaging().send("b", "t", new byte[0]).block())
                .isInstanceOf(PeerMessaging.PeerNotActiveException.class);
    }

    @Test
    void impossibleThresholdKeepsEveryoneInactive() {
        P2pService a = node("a", List.of(), 0.0001);   // sub-microsecond: no real RTT can pass
        node("b", List.of(a.address()), null);
        await().atMost(Duration.ofSeconds(15)).until(() -> !a.tableForTest().snapshot().isEmpty());
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(a.tableForTest().active()).isEmpty());
    }
}
