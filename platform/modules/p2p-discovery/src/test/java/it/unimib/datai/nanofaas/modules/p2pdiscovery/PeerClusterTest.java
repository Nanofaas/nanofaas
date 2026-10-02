package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import it.unimib.datai.nanofaas.modules.p2pdiscovery.PeerCluster.MemberEvent;
import io.scalecube.cluster.transport.api.Message;
import io.scalecube.cluster.transport.api.Transport;
import io.scalecube.cluster.transport.api.TransportConfig;
import io.scalecube.transport.netty.tcp.TcpTransportFactory;
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

class PeerClusterTest {
    private final List<PeerCluster> started = new ArrayList<>();

    @AfterEach
    void stopAll() {
        started.forEach(PeerCluster::close);
    }

    private PeerCluster node(String id, List<String> seeds) {
        PeerCluster c = new PeerCluster(id, 0, "127.0.0.1", seeds);
        c.start().block(Duration.ofSeconds(10));
        started.add(c);
        return c;
    }

    @Test
    void threeNodesDiscoverEachOtherThroughOneSeed() {
        PeerCluster a = node("a", List.of());
        node("b", List.of(a.address()));
        PeerCluster c = node("c", List.of(a.address()));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(a.members()).extracting(MemberEvent::id).containsExactlyInAnyOrder("b", "c");
            assertThat(c.members()).extracting(MemberEvent::id).containsExactlyInAnyOrder("a", "b");
        });
    }

    @Test
    void requestGetsTheHandlerReplyAndSendIsDelivered() {
        PeerCluster a = node("a", List.of());
        PeerCluster b = node("b", List.of(a.address()));
        List<String> got = new CopyOnWriteArrayList<>();
        b.handle("echo", (sender, payload) -> {
            got.add(new String(payload, StandardCharsets.UTF_8));
            return Mono.just("re:".getBytes(StandardCharsets.UTF_8));
        });
        await().until(() -> !a.members().isEmpty());

        byte[] reply = a.request(b.address(), "echo", "hi".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(3))
                .block(Duration.ofSeconds(5));
        assertThat(new String(reply, StandardCharsets.UTF_8)).isEqualTo("re:");

        a.send(b.address(), "echo", "one-way".getBytes(StandardCharsets.UTF_8)).block(Duration.ofSeconds(3));
        await().untilAsserted(() -> assertThat(got).containsExactly("hi", "one-way"));
    }

    @Test
    void closedNodeIsReportedRemoved() {
        PeerCluster a = node("a", List.of());
        PeerCluster b = node("b", List.of(a.address()));
        List<MemberEvent> events = new CopyOnWriteArrayList<>();
        a.events().subscribe(events::add);
        await().until(() -> !a.members().isEmpty());
        b.close();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(events).anySatisfy(e -> {
                    assertThat(e.id()).isEqualTo("b");
                    assertThat(e.type()).isEqualTo(PeerCluster.Type.REMOVED);
                }));
        assertThat(a.members()).isEmpty();
    }

    @Test
    void requestToUnreachableAddressFailsInsteadOfHanging() {
        PeerCluster a = node("a", List.of());
        var err = a.request("127.0.0.1:1", "echo", new byte[0], Duration.ofSeconds(1))
                .map(x -> "ok").onErrorReturn("err").block(Duration.ofSeconds(5));
        assertThat(err).isEqualTo("err");
    }

    @Test
    void malformedInboundMessagesDoNotStopTheInboundPath() {
        PeerCluster a = node("a", List.of());
        PeerCluster b = node("b", List.of(a.address()));
        b.handle("echo", (sender, payload) -> Mono.just("ok".getBytes(StandardCharsets.UTF_8)));
        await().until(() -> !a.members().isEmpty());

        Transport raw = Transport.bindAwait(TransportConfig.defaultConfig().transportFactory(new TcpTransportFactory()));
        try {
            String target = b.address();
            raw.send(target, Message.withQualifier("p2p.msg").sender("x:1").data(new byte[0]).build()).block();            // no topic
            raw.send(target, Message.withQualifier("p2p.msg").header("topic", "echo").sender("x:1").data("text").build()).block(); // data is not bytes
            raw.send(target, Message.withQualifier("p2p.msg").header("topic", "echo").data(new byte[0]).build()).block();   // no sender
            raw.send(target, Message.withQualifier("p2p.req").header("topic", "echo").correlationId("c").data(new byte[0]).build()).block();
        } finally {
            raw.stop().block();
        }

        byte[] reply = a.request(b.address(), "echo", new byte[0], Duration.ofSeconds(3)).block(Duration.ofSeconds(5));
        assertThat(new String(reply, StandardCharsets.UTF_8)).isEqualTo("ok");   // b still serves requests
    }
}
