package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class P2pInformationHttpTest {
    @Test void bothRoutesUseExistingGate() {
        for (boolean enabled : List.of(false, true)) {
            for (boolean admin : List.of(false, true)) {
                if (enabled && admin) continue;
                var props = new P2pProperties(enabled, "local", 0, null, List.of(), null, null,
                        Duration.ofSeconds(1), Duration.ofSeconds(2), null, new P2pProperties.Admin(admin));
                var settings = new P2pSettings(null, null);
                var client = WebTestClient.bindToController(new P2pAdminController(new PeerTable(settings::effective),
                        settings, mock(P2pService.class))).webFilter(new P2pAdminGate(props)).build();
                client.get().uri("/v1/admin/p2p/information").exchange().expectStatus().isNotFound();
                client.get().uri("/v1/admin/p2p/peers/remote/information").exchange().expectStatus().isNotFound();
            }
        }
    }

    @Test void typedInformationAndRuntimeConfigAreExposedOverHttp() {
        var service = mock(P2pService.class);
        var settings = new P2pSettings(null, null);
        var table = new PeerTable(settings::effective);
        var client = WebTestClient.bindToController(new P2pAdminController(table, settings, service)).build();
        when(service.localInformation()).thenReturn(NodeInformationExchangeTest.snapshot("local"));
        when(service.peerInformation("unknown")).thenReturn(Optional.empty());
        client.get().uri("/v1/admin/p2p/information").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.nodeId").isEqualTo("local").jsonPath("$.functions.data").isArray();
        client.get().uri("/v1/admin/p2p/peers/unknown/information").exchange().expectStatus().isNotFound();
        for (String state : List.of("NOT_RECEIVED", "STALE", "INACTIVE")) {
            when(service.peerInformation("remote")).thenReturn(Optional.of(
                    new NodeInformationExchange.RemoteInformation("remote", state, null)));
            client.get().uri("/v1/admin/p2p/peers/remote/information").exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.state").isEqualTo(state).jsonPath("$.information").isEmpty();
        }
        client.patch().uri("/v1/admin/p2p/config").bodyValue(Map.of("shareImages", true))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.effective.shareImages").isEqualTo(true);
        verify(service).informationSettingsChanged();
        client.patch().uri("/v1/admin/p2p/config").bodyValue(Map.of("shareImages", false, "shareResources", "true"))
                .exchange().expectStatus().isBadRequest();
        assertThat(settings.sharing().images()).isTrue();
        client.delete().uri("/v1/admin/p2p/overrides").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.effective.shareImages").isEqualTo(false);
    }
}
