package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** On-disk shape: {@code config} belongs to the operator, {@code state} to the node. */
public record P2pFile(Config config, State state) {
    public P2pFile {
        if (config == null) config = new Config(null, null, null, null);
        if (state == null) state = new State(null, null, null, null, null, null);
    }

    public record PeerEntry(String id, PeerMode mode) {}

    public record Config(List<String> seeds, Integer maxNeighbors, Double maxLatencyMs, List<PeerEntry> peers) {
        public Config {
            seeds = seeds == null ? List.of() : List.copyOf(seeds);
            peers = peers == null ? List.of() : List.copyOf(peers);
        }
    }

    public record KnownPeer(String id, String address, Double rttMs, List<Double> coord) {}

    public record State(String nodeId, List<Double> coord, Double coordError, Map<String, Object> overrides,
                        Map<String, PeerMode> peerModes, List<KnownPeer> peers) {
        public State {
            overrides = overrides == null ? Map.of() : new HashMap<>(overrides);   // HashMap: null values allowed
            peerModes = peerModes == null ? Map.of() : Map.copyOf(peerModes);
            peers = peers == null ? List.of() : List.copyOf(peers);
        }
    }
}
