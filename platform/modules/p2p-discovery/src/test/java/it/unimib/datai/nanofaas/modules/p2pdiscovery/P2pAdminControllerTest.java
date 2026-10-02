package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class P2pAdminControllerTest {
    private final P2pSettings settings = new P2pSettings(null, null);
    private final PeerTable table = new PeerTable(settings::effective);
    private final P2pService service = mock(P2pService.class);
    private final P2pAdminController ctl = new P2pAdminController(table, settings, service);

    @Test
    void peersListShowsStateAndReason() {
        table.upsert("a", "a:1");
        var body = ctl.peers().getBody();
        assertThat(body).hasSize(1);
        assertThat(body.getFirst().active()).isTrue();
        assertThat(body.getFirst().mode()).isEqualTo("AUTO");
    }

    @Test
    void putModeExcludesAndAutoRestores() {
        table.upsert("a", "a:1");
        assertThat(ctl.putPeer("a", Map.of("mode", "EXCLUDED")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(table.isActive("a")).isFalse();
        ctl.putPeer("a", Map.of("mode", "AUTO"));
        assertThat(table.isActive("a")).isTrue();
        verify(service, times(2)).requestPersist();
    }

    @Test
    void putModeAcceptsAPeerNotDiscoveredYet() {
        assertThat(ctl.putPeer("ghost", Map.of("mode", "EXCLUDED")).getStatusCode()).isEqualTo(HttpStatus.OK);
        table.upsert("ghost", "g:1");
        assertThat(table.isActive("ghost")).isFalse();
    }

    @Test
    void putBadModeIs400AndChangesNothing() {
        table.upsert("a", "a:1");
        assertThat(ctl.putPeer("a", Map.of("mode", "BANANA")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ctl.putPeer("a", Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).requestPersist();
    }

    @Test
    void patchConfigAppliesAndRecomputes() {
        table.upsert("a", "a:1"); table.recordRtt("a", 30, new Vivaldi.Coord(0, 0, 0));
        table.upsert("b", "b:1"); table.recordRtt("b", 10, new Vivaldi.Coord(0, 0, 0));
        ResponseEntity<?> r = ctl.patchConfig(Map.of("maxNeighbors", 1));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(table.isActive("b")).isTrue();
        assertThat(table.isActive("a")).isFalse();
    }

    @Test
    void patchConfigRejectsInvalidValuesWithoutPersisting() {
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("maxNeighbors", -1), Map.of("maxLatencyMs", 0), Map.of("maxLatencyMs", "x"), Map.of("nope", 1))) {
            assertThat(ctl.patchConfig(bad).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        verify(service, never()).requestPersist();
    }

    @Test
    void patchNullRemovesTheThreshold() {
        settings.setBase(null, 50.0);
        Map<String, Object> body = new HashMap<>();
        body.put("maxLatencyMs", null);
        ctl.patchConfig(body);
        assertThat(settings.effective().maxLatencyMs()).isNull();
    }

    @Test
    void deleteOverridesClearsSettingsAndApiModes() {
        table.upsert("a", "a:1");
        ctl.putPeer("a", Map.of("mode", "EXCLUDED"));
        ctl.patchConfig(Map.of("maxNeighbors", 0));
        ctl.deleteOverrides();
        assertThat(settings.overrides()).isEmpty();
        assertThat(table.apiModes()).isEmpty();
        assertThat(table.isActive("a")).isTrue();
    }
}
