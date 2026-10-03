package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class P2pSettingsTest {
    @Test
    void publicationSwitchesAreIndependentAtomicAndResettable() {
        P2pSettings s = new P2pSettings(null, null);
        assertThat(s.sharing()).isEqualTo(new P2pSettings.Sharing(false, false, false));
        for (int mask = 0; mask < 8; mask++) {
            s.patch(Map.of("shareFunctions", (mask & 1) != 0,
                    "shareImages", (mask & 2) != 0, "shareResources", (mask & 4) != 0));
            assertThat(s.sharing().functions()).isEqualTo((mask & 1) != 0);
            assertThat(s.sharing().images()).isEqualTo((mask & 2) != 0);
            assertThat(s.sharing().resources()).isEqualTo((mask & 4) != 0);
        }
        assertThatThrownBy(() -> s.patch(Map.of("shareFunctions", false, "shareImages", "yes")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(s.sharing().functions()).isTrue();
        Map<String, Object> invalid = new HashMap<>();
        invalid.put("shareResources", null);
        assertThatThrownBy(() -> s.patch(invalid)).isInstanceOf(IllegalArgumentException.class);
        s.clearOverrides();
        assertThat(s.sharing()).isEqualTo(new P2pSettings.Sharing(false, false, false));
    }
    @Test
    void overrideBeatsBaseAndNullClearsTheThreshold() {
        P2pSettings s = new P2pSettings(4, 80.0);
        s.patch(Map.of("maxNeighbors", 6));
        assertThat(s.effective().maxNeighbors()).isEqualTo(6);
        assertThat(s.effective().maxLatencyMs()).isEqualTo(80.0);
        Map<String, Object> clear = new HashMap<>();
        clear.put("maxLatencyMs", null);
        s.patch(clear);
        assertThat(s.effective().maxLatencyMs()).isNull();      // explicit null = "not applied"
        s.clearOverrides();
        assertThat(s.effective().maxNeighbors()).isEqualTo(4);
        assertThat(s.effective().maxLatencyMs()).isEqualTo(80.0);
    }

    @Test
    void invalidPatchesAreRejectedAndLeaveStateUntouched() {
        P2pSettings s = new P2pSettings(4, null);
        Map<String, Object> negative = Map.of("maxNeighbors", -1);
        Map<String, Object> zero = Map.of("maxLatencyMs", 0);
        Map<String, Object> text = Map.of("maxLatencyMs", "fast");
        Map<String, Object> unknown = Map.of("bogus", 1);
        assertThatThrownBy(() -> s.patch(negative)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.patch(zero)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.patch(text)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.patch(unknown)).isInstanceOf(IllegalArgumentException.class);
        assertThat(s.overrides()).isEmpty();
        assertThat(s.effective().maxNeighbors()).isEqualTo(4);
    }

    @Test
    void zeroNeighborsIsValid() {
        P2pSettings s = new P2pSettings(null, null);
        s.patch(Map.of("maxNeighbors", 0));
        assertThat(s.effective().maxNeighbors()).isZero();
    }

    @Test
    void baseValuesAreValidatedLikePatches() {
        assertThatThrownBy(() -> new P2pSettings(-3, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new P2pSettings(null, 0.0)).isInstanceOf(IllegalArgumentException.class);
        P2pSettings s = new P2pSettings(1, 10.0);
        assertThatThrownBy(() -> s.setBase(-1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(s.effective().maxNeighbors()).isEqualTo(1);   // a rejected base leaves the old one in place
    }
}
