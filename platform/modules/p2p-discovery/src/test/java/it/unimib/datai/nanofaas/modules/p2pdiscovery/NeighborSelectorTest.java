package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import it.unimib.datai.nanofaas.modules.p2pdiscovery.NeighborSelector.Candidate;
import it.unimib.datai.nanofaas.modules.p2pdiscovery.NeighborSelector.Decision;
import it.unimib.datai.nanofaas.modules.p2pdiscovery.NeighborSelector.Settings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NeighborSelectorTest {
    private final NeighborSelector selector = new NeighborSelector();

    private static Candidate c(String id, PeerMode mode, Double rtt) {
        return new Candidate(id, mode, rtt, null, false);
    }

    private static Decision d(Map<String, Decision> m, String id) {
        return m.get(id);
    }

    @Test
    void noThresholdNoMaxMeansEveryoneActiveEvenUnmeasured() {
        var r = selector.select(List.of(c("a", PeerMode.AUTO, 500.0), c("b", PeerMode.AUTO, null)),
                new Settings(null, null));
        assertThat(d(r, "a").active()).isTrue();
        assertThat(d(r, "b").active()).isTrue();
    }

    @Test
    void excludedIsNeverActive() {
        var r = selector.select(List.of(c("a", PeerMode.EXCLUDED, 1.0)), new Settings(null, null));
        assertThat(d(r, "a")).isEqualTo(new Decision(false, "excluded"));
    }

    @Test
    void forcedIgnoresThresholdAndMax() {
        var r = selector.select(List.of(c("a", PeerMode.FORCE_ACTIVE, 999.0), c("b", PeerMode.AUTO, 1.0)),
                new Settings(1, 10.0));
        assertThat(d(r, "a")).isEqualTo(new Decision(true, "forced"));
        assertThat(d(r, "b").active()).isFalse();           // forced peer consumed the only slot
        assertThat(d(r, "b").reason()).isEqualTo("over-max-neighbors");
    }

    @Test
    void thresholdSetCutsSlowAndUnmeasuredPeers() {
        var r = selector.select(List.of(c("fast", PeerMode.AUTO, 20.0), c("slow", PeerMode.AUTO, 90.0),
                c("new", PeerMode.AUTO, null)), new Settings(null, 50.0));
        assertThat(d(r, "fast").active()).isTrue();
        assertThat(d(r, "slow")).isEqualTo(new Decision(false, "latency"));
        assertThat(d(r, "new")).isEqualTo(new Decision(false, "no-measurement"));
    }

    @Test
    void restoredRttNeverSatisfiesTheThreshold() {
        var restored = new Candidate("p", PeerMode.AUTO, null, 5.0, false);
        var r = selector.select(List.of(restored), new Settings(null, 50.0));
        assertThat(d(r, "p")).isEqualTo(new Decision(false, "no-measurement"));
    }

    @Test
    void maxNeighborsKeepsLowestRttUnmeasuredLastRestoredHintOrders() {
        var r = selector.select(List.of(c("a", PeerMode.AUTO, 30.0), c("b", PeerMode.AUTO, 10.0),
                new Candidate("c", PeerMode.AUTO, null, 20.0, false), c("d", PeerMode.AUTO, null)),
                new Settings(2, null));
        assertThat(d(r, "b").active()).isTrue();
        assertThat(d(r, "c").active()).isTrue();            // hint 20 beats measured 30 for ordering
        assertThat(d(r, "a")).isEqualTo(new Decision(false, "over-max-neighbors"));
        assertThat(d(r, "d").active()).isFalse();
    }

    @Test
    void maxNeighborsZeroActivatesOnlyForced() {
        var r = selector.select(List.of(c("a", PeerMode.AUTO, 1.0), c("f", PeerMode.FORCE_ACTIVE, 1.0)),
                new Settings(0, null));
        assertThat(d(r, "a").active()).isFalse();
        assertThat(d(r, "f").active()).isTrue();
    }

    @Test
    void hysteresisKeepsActivePeerJustAboveThreshold() {
        var wasActive = new Candidate("a", PeerMode.AUTO, 52.0, null, true);   // 4% over 50, within +10%
        var wasIdle = new Candidate("b", PeerMode.AUTO, 52.0, null, false);
        var r = selector.select(List.of(wasActive, wasIdle), new Settings(null, 50.0));
        assertThat(d(r, "a").active()).isTrue();
        assertThat(d(r, "b")).isEqualTo(new Decision(false, "latency"));
    }

    @Test
    void activePeerFarAboveThresholdIsDropped() {
        var r = selector.select(List.of(new Candidate("a", PeerMode.AUTO, 60.0, null, true)),
                new Settings(null, 50.0));
        assertThat(d(r, "a")).isEqualTo(new Decision(false, "latency"));
    }

    @Test
    void tieBreaksById() {
        var r = selector.select(List.of(c("b", PeerMode.AUTO, 10.0), c("a", PeerMode.AUTO, 10.0)),
                new Settings(1, null));
        assertThat(d(r, "a").active()).isTrue();
        assertThat(d(r, "b").active()).isFalse();
    }

    @Test
    void anActiveNeighborKeepsItsSlotAgainstAnAlmostEqualChallenger() {
        var holder = new Candidate("a", PeerMode.AUTO, 1.05, null, true);
        var challenger = new Candidate("b", PeerMode.AUTO, 1.0, null, false);
        var r = selector.select(List.of(holder, challenger), new Settings(1, null));
        assertThat(d(r, "a").active()).isTrue();     // median jitter must not swap them back and forth
        assertThat(d(r, "b").active()).isFalse();
    }

    @Test
    void aClearlyBetterChallengerStillTakesTheSlot() {
        var holder = new Candidate("a", PeerMode.AUTO, 30.0, null, true);
        var challenger = new Candidate("b", PeerMode.AUTO, 10.0, null, false);
        var r = selector.select(List.of(holder, challenger), new Settings(1, null));
        assertThat(d(r, "b").active()).isTrue();
        assertThat(d(r, "a").active()).isFalse();
    }
}
