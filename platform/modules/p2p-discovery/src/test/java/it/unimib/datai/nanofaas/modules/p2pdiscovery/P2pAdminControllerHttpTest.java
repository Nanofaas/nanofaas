package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Goes through the real annotation wiring (path variables, bodies), which direct method calls skip. */
class P2pAdminControllerHttpTest {
    private final P2pSettings settings = new P2pSettings(null, null);
    private final PeerTable table = new PeerTable(settings::effective);
    private final WebTestClient http = WebTestClient
            .bindToController(new P2pAdminController(table, settings, org.mockito.Mockito.mock(P2pService.class)))
            .configureClient().baseUrl("/v1/admin/p2p").build();

    @Test
    void putPeerBindsThePathVariable() {
        table.upsert("edge-9", "e:1");
        http.put().uri("/peers/edge-9").bodyValue(Map.of("mode", "EXCLUDED"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.id").isEqualTo("edge-9");
        assertThat(table.isActive("edge-9")).isFalse();
    }

    @Test
    void patchAndGetConfigRoundTripOverHttp() {
        http.patch().uri("/config").bodyValue(Map.of("maxNeighbors", 2))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.effective.maxNeighbors").isEqualTo(2);
        http.get().uri("/config").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.overrides.maxNeighbors").isEqualTo(2);
        http.patch().uri("/config").bodyValue(Map.of("maxNeighbors", -1))
                .exchange().expectStatus().isBadRequest();
    }
}
