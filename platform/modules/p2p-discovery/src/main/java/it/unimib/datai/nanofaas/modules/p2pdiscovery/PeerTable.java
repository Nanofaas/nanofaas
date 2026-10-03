package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import it.unimib.datai.nanofaas.modules.p2pdiscovery.NeighborSelector.Candidate;
import it.unimib.datai.nanofaas.modules.p2pdiscovery.NeighborSelector.Decision;
import it.unimib.datai.nanofaas.modules.p2pdiscovery.Vivaldi.Coord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Peer state plus the selector's verdicts. All methods are synchronized; peers are few. */
public final class PeerTable {
    private static final int RTT_WINDOW = 9;

    public record Peer(String id, String address, PeerMode mode, Double rttMs, List<Double> coord,
                       boolean active, String reason) {}

    private static final class State {
        String address;
        final RttWindow rtt = new RttWindow(RTT_WINDOW);
        Double hintMs;
        Coord coord = new Coord(0, 0, 0);
        boolean active;
        long generation;
        String reason = "new";
    }

    private final Supplier<NeighborSelector.Settings> settings;
    private final NeighborSelector selector = new NeighborSelector();
    private boolean available = true;
    private long nextGeneration;
    private final Map<String, State> peers = new LinkedHashMap<>();
    private final Map<String, PeerMode> operatorModes = new HashMap<>();
    private final Map<String, PeerMode> apiModes = new HashMap<>();

    public PeerTable(Supplier<NeighborSelector.Settings> settings) {
        this.settings = settings;
    }

    public synchronized void upsert(String id, String address) {
        State s = peers.computeIfAbsent(id, k -> new State());
        if (!java.util.Objects.equals(s.address, address)) s.generation = ++nextGeneration;
        s.address = address;
        recompute();
    }

    public synchronized void remove(String id) {
        peers.remove(id);
        recompute();
    }

    public synchronized void recordRtt(String id, double rttMs, Coord coord) {
        State s = peers.get(id);
        if (s == null) return;
        s.rtt.add(rttMs);
        s.coord = coord;
        recompute();
    }

    /** A ping that got no answer counts as a sample at the timeout, so a silent peer ages out of the threshold. */
    public synchronized void recordTimeout(String id, double timeoutMs) {
        State s = peers.get(id);
        if (s == null) return;
        s.rtt.add(timeoutMs);
        recompute();
    }

    public synchronized void restore(String id, String address, Double rttHintMs, Coord coord) {
        State s = peers.computeIfAbsent(id, k -> new State());
        if (!java.util.Objects.equals(s.address, address)) s.generation = ++nextGeneration;
        s.address = address;
        s.hintMs = rttHintMs;
        s.coord = coord;
        recompute();
    }

    public synchronized void setOperatorMode(String id, PeerMode mode) {
        operatorModes.put(id, mode);
        recompute();
    }

    public synchronized void setApiMode(String id, PeerMode modeOrNull) {
        if (modeOrNull == null) apiModes.remove(id);
        else apiModes.put(id, modeOrNull);
        recompute();
    }

    public synchronized Map<String, PeerMode> apiModes() {
        return new HashMap<>(apiModes);
    }

    public synchronized void loadApiModes(Map<String, PeerMode> modes) {
        apiModes.clear();
        if (modes != null) apiModes.putAll(modes);
        recompute();
    }

    public synchronized List<Peer> snapshot() {
        List<Peer> out = new ArrayList<>();
        peers.forEach((id, s) -> {
            Double rtt = s.rtt.median();
            out.add(new Peer(id, s.address, mode(id), rtt != null ? rtt : s.hintMs, s.coord.toList(), s.active, s.reason));
        });
        out.sort(Comparator.comparing(Peer::id));
        return out;
    }

    public synchronized List<Peer> active() {
        return snapshot().stream().filter(Peer::active).toList();
    }

    public synchronized boolean isActive(String id) {
        State s = peers.get(id);
        return s != null && s.active;
    }

    /** Changes on address or participation transitions, including exclusion followed by reactivation. */
    public synchronized long activationGeneration(String id) {
        State s = peers.get(id);
        return s != null && s.active ? s.generation : -1;
    }

    /** The address when the peer is an active neighbor, in one step (no gap between "is active" and "where"). */
    public synchronized Optional<String> activeAddressOf(String id) {
        State s = peers.get(id);
        return s != null && s.active ? Optional.ofNullable(s.address) : Optional.empty();
    }

    public synchronized Optional<String> addressOf(String id) {
        return Optional.ofNullable(peers.get(id)).map(s -> s.address);
    }

    public synchronized Optional<String> idOf(String address) {
        return peers.entrySet().stream().filter(e -> address.equals(e.getValue().address))
                .map(Map.Entry::getKey).findFirst();
    }

    /** Controls local availability without changing operator or API peer modes. */
    public synchronized void setAvailable(boolean available) {
        this.available = available;
        recompute();
    }

    public synchronized void clearPeers() {
        peers.clear();
    }

    public synchronized void recompute() {
        if (!available) {
            peers.values().forEach(s -> {
                if (s.active) s.generation = ++nextGeneration;
                s.active = false; s.reason = "unavailable";
            });
            return;
        }
        List<Candidate> cands = new ArrayList<>();
        peers.forEach((id, s) -> cands.add(new Candidate(id, mode(id), s.rtt.median(), s.hintMs, s.active)));
        Map<String, Decision> decisions = selector.select(cands, settings.get());
        decisions.forEach((id, d) -> {
            State s = peers.get(id);
            if (s.active != d.active()) s.generation = ++nextGeneration;
            s.active = d.active();
            s.reason = d.reason();
        });
    }

    private PeerMode mode(String id) {
        PeerMode api = apiModes.get(id);
        if (api != null) return api;
        return operatorModes.getOrDefault(id, PeerMode.AUTO);
    }
}
