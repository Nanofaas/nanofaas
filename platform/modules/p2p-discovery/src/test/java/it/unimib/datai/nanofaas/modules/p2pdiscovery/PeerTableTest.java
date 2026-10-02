package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import it.unimib.datai.nanofaas.modules.p2pdiscovery.NeighborSelector.Settings;
import it.unimib.datai.nanofaas.modules.p2pdiscovery.Vivaldi.Coord;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PeerTableTest {
    private final AtomicReference<Settings> settings = new AtomicReference<>(new Settings(null, null));
    private final PeerTable table = new PeerTable(settings::get);
    private static final Coord O = new Coord(0, 0, 0);

    @Test
    void newPeerIsActiveWhenNoRulesApply() {
        table.upsert("a", "10.0.0.1:1");
        assertThat(table.isActive("a")).isTrue();
    }

    @Test
    void thresholdActivatesPeerOnlyAfterAFastMeasurement() {
        settings.set(new Settings(null, 50.0));
        table.upsert("a", "10.0.0.1:1");
        assertThat(table.isActive("a")).isFalse();
        table.recordRtt("a", 20, O);
        assertThat(table.isActive("a")).isTrue();
    }

    @Test
    void apiModeBeatsOperatorModeAndClearingFallsBack() {
        table.upsert("a", "x:1");
        table.setOperatorMode("a", PeerMode.EXCLUDED);
        assertThat(table.isActive("a")).isFalse();
        table.setApiMode("a", PeerMode.FORCE_ACTIVE);
        assertThat(table.isActive("a")).isTrue();
        table.setApiMode("a", null);
        assertThat(table.isActive("a")).isFalse();     // back to the operator's EXCLUDED
    }

    @Test
    void modeSetBeforeThePeerIsDiscoveredApplies() {
        table.setOperatorMode("late", PeerMode.EXCLUDED);
        table.upsert("late", "x:1");
        assertThat(table.isActive("late")).isFalse();
    }

    @Test
    void rejoinWithNewAddressFollowsTheId() {
        table.upsert("a", "old:1");
        table.upsert("a", "new:2");
        assertThat(table.addressOf("a")).contains("new:2");
        assertThat(table.idOf("old:1")).isEmpty();
        assertThat(table.idOf("new:2")).contains("a");
    }

    @Test
    void removedPeerDisappears() {
        table.upsert("a", "x:1");
        table.remove("a");
        assertThat(table.snapshot()).isEmpty();
        assertThat(table.isActive("a")).isFalse();
    }

    @Test
    void restoredHintOrdersButNeverPassesTheThreshold() {
        settings.set(new Settings(null, 50.0));
        table.restore("a", "x:1", 5.0, O);
        assertThat(table.isActive("a")).isFalse();
        assertThat(table.snapshot().getFirst().reason()).isEqualTo("no-measurement");
    }

    @Test
    void recomputeAppliesSettingsChanges() {
        table.upsert("a", "x:1");
        table.recordRtt("a", 30, O);
        table.upsert("b", "y:1");
        table.recordRtt("b", 10, O);
        settings.set(new Settings(1, null));
        table.recompute();
        assertThat(table.isActive("b")).isTrue();
        assertThat(table.isActive("a")).isFalse();
        assertThat(table.active()).extracting(PeerTable.Peer::id).containsExactly("b");
    }

    @Test
    void activeAddressOfAnswersInOneStepAndOnlyForActivePeers() {
        table.upsert("a", "a:1");
        table.upsert("b", "b:1");
        table.setApiMode("b", PeerMode.EXCLUDED);
        assertThat(table.activeAddressOf("a")).contains("a:1");
        assertThat(table.activeAddressOf("b")).isEmpty();
        assertThat(table.activeAddressOf("nobody")).isEmpty();
    }
}
