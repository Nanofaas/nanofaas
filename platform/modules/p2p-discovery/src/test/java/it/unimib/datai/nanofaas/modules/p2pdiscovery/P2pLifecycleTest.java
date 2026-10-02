package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class P2pLifecycleTest {
    private final Map<P2pService, P2pSettings> settings = new java.util.IdentityHashMap<>();

    private P2pService node(String id, List<String> seeds, boolean enabled) {
        var props = new P2pProperties(enabled, id, 0, "127.0.0.1", seeds, null, null,
                Duration.ofMillis(200), Duration.ofMillis(300), null, null);
        var settings = new P2pSettings(null, null);
        var service = new P2pService(props, new PeerTable(settings::effective), settings, new SimpleMeterRegistry());
        this.settings.put(service, settings);
        return service;
    }

    @Test
    void aStandaloneDeploymentCannotBeActivatedThroughTheApi() {
        var service = node("standalone", List.of(), false);
        service.start();
        assertThat(service.state()).isEqualTo(P2pService.State.DISABLED);
        assertThatThrownBy(() -> service.setState(P2pService.State.ACTIVE)).isInstanceOf(IllegalStateException.class);
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    void threeNodesDistinguishFailureFromLeaveAndRejoinWithTheirSettingsAndHandlers() {
        var a = node("a", List.of(), true);
        a.start();
        var b = node("b", List.of(a.address()), true);
        var c = node("c", List.of(a.address()), true);
        try {
            b.start();
            c.start();
            await().atMost(Duration.ofSeconds(10)).until(() -> a.tableForTest().snapshot().size() == 2
                    && b.tableForTest().snapshot().size() == 2 && c.tableForTest().snapshot().size() == 2);
            String address = b.address();
            var controller = new P2pAdminController(b.tableForTest(), settings.get(b), b);
            controller.patchConfig(Map.of("maxNeighbors", 1));
            controller.putPeer("c", Map.of("mode", "EXCLUDED"));
            b.messaging().subscribe("echo", (sender, bytes) -> Mono.just(bytes));
            b.setState(P2pService.State.ISOLATED);
            b.setState(P2pService.State.ISOLATED);
            assertThat(b.state()).isEqualTo(P2pService.State.ISOLATED);
            assertThat(b.tableForTest().active()).isEmpty();
            assertThatThrownBy(b::messaging).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> a.messaging().request("b", "echo", new byte[]{1}, Duration.ofMillis(300)).block())
                    .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
            // Isolation sends no LEAVING: membership must initially retain b, then detect its failure.
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4))
                    .untilAsserted(() -> assertThat(a.tableForTest().snapshot()).extracting(PeerTable.Peer::id).contains("b"));
            await().atMost(Duration.ofSeconds(30)).until(() -> a.tableForTest().snapshot().size() == 1
                    && c.tableForTest().snapshot().size() == 1);
            b.setState(P2pService.State.ACTIVE);
            b.setState(P2pService.State.ACTIVE);
            await().atMost(Duration.ofSeconds(45)).until(() -> a.tableForTest().snapshot().size() == 2
                    && b.tableForTest().snapshot().size() == 2 && c.tableForTest().snapshot().size() == 2);
            assertThat(b.address()).isEqualTo(address);
            assertThat(settings.get(b).effective().maxNeighbors()).isEqualTo(1);
            assertThat(b.tableForTest().apiModes()).containsEntry("c", PeerMode.EXCLUDED);
            assertThat(a.messaging().request("b", "echo", new byte[]{7}, Duration.ofSeconds(2)).block()).containsExactly(7);
            b.setState(P2pService.State.LEFT);
            b.setState(P2pService.State.LEFT);
            await().atMost(Duration.ofSeconds(3)).until(() -> a.tableForTest().snapshot().size() == 1
                    && c.tableForTest().snapshot().size() == 1);
            assertThat(b.tableForTest().snapshot()).isEmpty();
            assertThatThrownBy(b::messaging).isInstanceOf(IllegalStateException.class);
            b.setState(P2pService.State.ACTIVE);
            await().atMost(Duration.ofSeconds(45)).until(() -> a.tableForTest().snapshot().size() == 2);
            assertThat(b.address()).isEqualTo(address);
            assertThat(b.tableForTest().apiModes()).containsEntry("c", PeerMode.EXCLUDED);
        } finally {
            b.stop();
            c.stop();
            a.stop();
        }
    }

    @Test
    void stateApiValidatesBodiesAndKeepsWorkingAfterLeave() {
        var service = node("api", List.of(), true);
        service.start();
        try {
            var http = WebTestClient.bindToController(new P2pAdminController(service.tableForTest(),
                    settings.get(service), service)).configureClient()
                    .responseTimeout(Duration.ofSeconds(20)).baseUrl("/v1/admin/p2p").build();
            http.get().uri("/state").exchange().expectStatus().isOk().expectBody().jsonPath("$.state").isEqualTo("ACTIVE");
            for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of(), Map.of("state", "bad"),
                    Map.of("state", "DISABLED"), Map.of("state", "LEFT", "extra", true))) {
                http.put().uri("/state").bodyValue(bad).exchange().expectStatus().isBadRequest();
            }
            for (String state : List.of("ISOLATED", "LEFT", "LEFT", "ACTIVE")) {
                http.put().uri("/state").bodyValue(Map.of("state", state)).exchange().expectStatus().isOk()
                        .expectBody().jsonPath("$.state").isEqualTo(state);
                http.get().uri("/state").exchange().expectStatus().isOk()
                        .expectBody().jsonPath("$.state").isEqualTo(state);
            }
        } finally {
            service.stop();
        }
    }
}
