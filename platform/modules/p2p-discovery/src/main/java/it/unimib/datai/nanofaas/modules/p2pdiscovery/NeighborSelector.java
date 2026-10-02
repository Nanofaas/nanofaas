package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure neighbor-selection rules; see the design spec, section "Selezione dei vicini". */
public final class NeighborSelector {
    /** A peer already active stays active up to threshold * (1 + HYSTERESIS). */
    static final double HYSTERESIS = 0.10;
    /** Ranking: an active neighbor also keeps its slot against a challenger less than this many ms better. */
    static final double MIN_RANK_MARGIN_MS = 1.0;

    public record Settings(Integer maxNeighbors, Double maxLatencyMs) {}

    public record Candidate(String id, PeerMode mode, Double rttMs, Double orderHintMs, boolean wasActive) {}

    public record Decision(boolean active, String reason) {}

    public Map<String, Decision> select(List<Candidate> peers, Settings settings) {
        Map<String, Decision> out = new HashMap<>();
        List<Candidate> pool = new ArrayList<>();
        int forced = 0;
        for (Candidate p : peers) {
            if (p.mode() == PeerMode.EXCLUDED) {
                out.put(p.id(), new Decision(false, "excluded"));
            } else if (p.mode() == PeerMode.FORCE_ACTIVE) {
                out.put(p.id(), new Decision(true, "forced"));
                forced++;
            } else if (settings.maxLatencyMs() != null && p.rttMs() == null) {
                out.put(p.id(), new Decision(false, "no-measurement"));
            } else if (settings.maxLatencyMs() != null && p.rttMs() > limit(p, settings.maxLatencyMs())) {
                out.put(p.id(), new Decision(false, "latency"));
            } else {
                pool.add(p);
            }
        }
        pool.sort(Comparator
                .comparingDouble(NeighborSelector::rank)
                .thenComparing(Candidate::id));
        int capacity = settings.maxNeighbors() == null ? Integer.MAX_VALUE : Math.max(0, settings.maxNeighbors() - forced);
        for (int i = 0; i < pool.size(); i++) {
            out.put(pool.get(i).id(), i < capacity ? new Decision(true, "selected")
                    : new Decision(false, "over-max-neighbors"));
        }
        return out;
    }

    private static double limit(Candidate p, double max) {
        return p.wasActive() ? max * (1 + HYSTERESIS) : max;
    }

    /** Sort key: the order value, lowered for an active neighbor so jitter does not swap near-equal peers. */
    private static double rank(Candidate p) {
        double order = order(p);
        return p.wasActive() ? order - Math.max(HYSTERESIS * order, MIN_RANK_MARGIN_MS) : order;
    }

    /** Measured RTT first, restored hint second, unknown last. */
    private static double order(Candidate p) {
        if (p.rttMs() != null) return p.rttMs();
        if (p.orderHintMs() != null) return p.orderHintMs();
        return Double.MAX_VALUE;
    }
}
