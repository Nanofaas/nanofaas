package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;

class P2pAdminGateTest {
    private static P2pProperties props(boolean enabled, boolean adminEnabled) {
        return new P2pProperties(enabled, null, 0, null, List.of(), null, null, Duration.ofSeconds(1),
                Duration.ofSeconds(2), null, new P2pProperties.Admin(adminEnabled));
    }

    private static WebTestClient client(P2pProperties p) {
        P2pSettings s = new P2pSettings(null, null);
        return WebTestClient.bindToController(new P2pAdminController(new PeerTable(s::effective), s, org.mockito.Mockito.mock(P2pService.class)))
                .webFilter(new P2pAdminGate(p)).configureClient().baseUrl("/v1/admin/p2p").build();
    }

    @Test
    void adminApiIs404WhenTheModuleIsDisabled() {
        client(props(false, true)).get().uri("/peers").exchange().expectStatus().isNotFound();
    }

    @Test
    void adminApiIs404WhenOnlyTheModuleIsEnabled() {
        client(props(true, false)).get().uri("/config").exchange().expectStatus().isNotFound();
    }

    @Test
    void adminApiIsServedWhenBothSwitchesAreOn() {
        client(props(true, true)).get().uri("/peers").exchange().expectStatus().isOk();
    }
}
