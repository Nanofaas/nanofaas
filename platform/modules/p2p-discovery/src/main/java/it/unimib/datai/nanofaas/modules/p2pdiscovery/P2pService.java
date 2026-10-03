package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.modules.p2pdiscovery.P2pFile.KnownPeer;
import it.unimib.datai.nanofaas.modules.p2pdiscovery.P2pFile.PeerEntry;
import it.unimib.datai.nanofaas.modules.p2pdiscovery.Vivaldi.Coord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Wires cluster, table, monitor and state file. A no-op unless nanofaas.p2p.enabled=true. */
public class P2pService implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(P2pService.class);
    private static final Duration PERSIST_EVERY = Duration.ofSeconds(5);
    /** RTTs and coordinates drift constantly; rewriting the file for them every few seconds would wear flash. */
    private static final Duration REFRESH_EVERY = Duration.ofMinutes(5);
    /** How long a saved peer that the cluster never confirms is still carried in the file. */
    private static final Duration HINT_TTL = Duration.ofMinutes(10);

    private final P2pProperties props;
    private final PeerTable table;
    private final P2pSettings settings;
    private final MeterRegistry meters;
    private final NodeInformationCollector informationCollector;
    private volatile NodeInformationExchange information;
    private final Vivaldi vivaldi = new Vivaldi(System.nanoTime());
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final Map<String, Meter.Id> peerGauges = new ConcurrentHashMap<>();
    private final Duration persistEvery;
    private final Duration hintTtl;
    private volatile long startedNanos;
    private final Map<String, KnownPeer> hints = new ConcurrentHashMap<>();
    public enum State { DISABLED, ACTIVE, ISOLATED, LEFT }

    private volatile State state = State.LEFT;
    private volatile boolean running;
    private LatencyMonitor monitor;
    private final AtomicReference<PeerCluster> cluster = new AtomicReference<>();
    private final AtomicReference<PeerMessaging> messaging = new AtomicReference<>();
    private final AtomicReference<P2pStateFile> stateFile = new AtomicReference<>();
    private volatile String nodeId;
    private final Object persistLock = new Object();
    private volatile boolean persistFailing;
    private Disposable pings;
    private Disposable events;
    private Disposable persister;
    private Disposable refresher;

    public P2pService(P2pProperties props, PeerTable table, P2pSettings settings, MeterRegistry meters) {
        this(props, table, settings, meters, PERSIST_EVERY, HINT_TTL);
    }

    public P2pService(P2pProperties props, PeerTable table, P2pSettings settings, MeterRegistry meters,
                      NodeInformationCollector collector) {
        this(props, table, settings, meters, PERSIST_EVERY, HINT_TTL, collector);
    }

    P2pService(P2pProperties props, PeerTable table, P2pSettings settings, MeterRegistry meters,
               Duration persistEvery, Duration hintTtl) {
        this(props, table, settings, meters, persistEvery, hintTtl,
                new NodeInformationCollector(null, null, null, meters, java.time.Clock.systemUTC(),
                        NodeInformationCollector::visibleMemory));
    }

    private P2pService(P2pProperties props, PeerTable table, P2pSettings settings, MeterRegistry meters,
                       Duration persistEvery, Duration hintTtl, NodeInformationCollector collector) {
        this.props = props;
        this.table = table;
        this.settings = settings;
        this.meters = meters;
        this.informationCollector = collector;
        this.persistEvery = persistEvery;
        this.hintTtl = hintTtl;
    }

    @Override
    public synchronized void start() {
        if (!Boolean.TRUE.equals(props.enabled()) || running) return;
        boolean generated = false;
        if (nodeId == null) {
            P2pFile file = openStateFile();
            generated = props.nodeId() == null && file.state().nodeId() == null;
            nodeId = resolveNodeId(file);
            applyFileConfig(file);
            information = new NodeInformationExchange(nodeId, settings, table, informationCollector, System::nanoTime);
            cluster.set(new PeerCluster(nodeId, props.port(), props.externalHost(), seedsFor(file)));
        }
        join();
        running = true;
        if (generated) persistNow();
    }

    private void join() {
        PeerCluster c = cluster.get();
        c.setIsolated(false);
        table.clearPeers();
        table.setAvailable(true);
        try {
            c.start().block(Duration.ofSeconds(15));
            wireUp(c);
            state = State.ACTIVE;
            information.activate(messaging.get(), true);
            log.info("p2p-discovery started: node {} at {}", nodeId, c.address());
        } catch (RuntimeException e) {
            disposeLoops();
            table.setAvailable(false);
            state = State.LEFT;
            try { c.close(); } catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    public State state() {
        return Boolean.TRUE.equals(props.enabled()) ? state : State.DISABLED;
    }

    /** Called on a worker by the admin API. Serializes transitions with startup and shutdown. */
    public synchronized State setState(State target) {
        if (!Boolean.TRUE.equals(props.enabled()) || !running) {
            throw new IllegalStateException("p2p-discovery is not enabled and running");
        }
        if (target == null || target == State.DISABLED) {
            throw new IllegalArgumentException("state must be ACTIVE, ISOLATED or LEFT");
        }
        if (state == target) return state;
        PeerCluster c = cluster.get();
        if (target == State.ISOLATED) {
            if (state == State.LEFT) join();
            information.deactivate();
            c.setIsolated(true);
            pings.dispose();
            table.setAvailable(false);
            state = State.ISOLATED;
        } else {
            boolean wasIsolated = state == State.ISOLATED;
            disposeLoops();
            table.setAvailable(false);
            state = State.LEFT;
            c.close(); // remains silent if isolated, so recovery does not announce a voluntary leave
            clearPeerMetrics();
            table.clearPeers();
            if (target == State.ACTIVE) {
                join();
            } else if (wasIsolated) {
                // Rejoin first: even peers that declared us dead can observe a voluntary LEAVING.
                join();
                disposeLoops();
                table.setAvailable(false);
                state = State.LEFT;
                c.close();
                clearPeerMetrics();
                table.clearPeers();
            }
        }
        persistNow();
        return state;
    }

    private void clearPeerMetrics() {
        peerGauges.values().forEach(meters::remove);
        peerGauges.clear();
    }

    private void wireUp(PeerCluster c) {
        if (messaging.get() == null) registerAggregateMetrics();
        events = c.events().subscribe(this::onMember);
        c.members().forEach(m -> admit(m.id(), m.address()));

        PeerMessaging.Wire wire = PeerMessaging.wire(c);
        if (messaging.get() == null) {
            messaging.set(new PeerMessaging(wire, table));
            monitor = new LatencyMonitor(wire, table, vivaldi, props.pingTimeout(), System::nanoTime);
            monitor.register();
        }
        pings = monitor.schedule(props.pingInterval());
        // file I/O must not run on the shared parallel scheduler
        persister = Flux.interval(persistEvery, Schedulers.boundedElastic()).subscribe(i -> {
            if (dirty.compareAndSet(true, false)) {
                persistNow();
            }
        }, e -> log.error("the state persister stopped", e));
        refresher = Flux.interval(REFRESH_EVERY).subscribe(i -> dirty.set(true));
        startedNanos = System.nanoTime();
    }

    /** Started before the web server (which starts at MAX_VALUE - 1), so admin calls never meet a half-started node. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 2048;
    }

    private P2pFile openStateFile() {
        String path = props.stateFile();
        if (path == null || path.isBlank()) {
            return new P2pFile(null, null);
        }
        P2pStateFile f = new P2pStateFile(Path.of(path));
        stateFile.set(f);
        f.removeStaleTempFiles();
        return f.load();
    }

    private String resolveNodeId(P2pFile file) {
        if (props.nodeId() != null) {
            return props.nodeId();
        }
        String saved = file.state().nodeId();
        return saved != null ? saved : UUID.randomUUID().toString();
    }

    /** Precedence: state overrides > file config > application.yml. */
    private void applyFileConfig(P2pFile file) {
        var fc = file.config();
        settings.setBaseSharing(new P2pSettings.Sharing(
                fc.shareFunctions() == null ? props.shareFunctions() : fc.shareFunctions(),
                fc.shareImages() == null ? props.shareImages() : fc.shareImages(),
                fc.shareResources() == null ? props.shareResources() : fc.shareResources()));
        try {
            settings.setBase(fc.maxNeighbors() != null ? fc.maxNeighbors() : props.maxNeighbors(),
                    fc.maxLatencyMs() != null ? fc.maxLatencyMs() : props.maxLatencyMs());
        } catch (IllegalArgumentException e) {
            log.warn("ignoring invalid values in the p2p file config, using application.yml: {}", e.getMessage());
            settings.setBase(props.maxNeighbors(), props.maxLatencyMs());
        }
        settings.loadOverrides(file.state().overrides());
        for (PeerEntry e : fc.peers()) {
            table.setOperatorMode(e.id(), e.mode() == null ? PeerMode.AUTO : e.mode());
        }
        table.loadApiModes(file.state().peerModes());
        vivaldi.restore(Coord.fromList(file.state().coord()),
                file.state().coordError() == null ? 1.0 : file.state().coordError());
    }

    /** Configured seeds plus the saved peers' addresses, which stay hints until the cluster confirms them. */
    private List<String> seedsFor(P2pFile file) {
        Set<String> seeds = new LinkedHashSet<>(props.seeds());
        seeds.addAll(file.config().seeds());
        for (KnownPeer k : file.state().peers()) {
            hints.put(k.id(), k);
            seeds.add(k.address());
        }
        return List.copyOf(seeds);
    }

    private void onMember(PeerCluster.MemberEvent e) {
        if (e.type() == PeerCluster.Type.ADDED) {
            admit(e.id(), e.address());
        } else {
            table.remove(e.id());
            Meter.Id gauge = peerGauges.remove(e.id());
            if (gauge != null) {
                meters.remove(gauge);
            }
        }
        dirty.set(true);
    }

    /** A cluster-confirmed peer enters the table, seeded with its saved hint (ordering only, never the threshold). */
    private void admit(String id, String address) {
        table.upsert(id, address);
        KnownPeer hint = hints.remove(id);
        if (hint != null) {
            table.restore(id, address, hint.rttMs(), Coord.fromList(hint.coord()));
        }
        peerGauges.computeIfAbsent(id, k -> Gauge.builder("p2p_peer_rtt_ms", table, t -> t.snapshot().stream()
                        .filter(p -> p.id().equals(id)).findFirst()
                        .map(p -> p.rttMs() == null ? Double.NaN : p.rttMs()).orElse(Double.NaN))
                .tag("peer", id).register(meters).getId());
    }

    @Override
    public synchronized void stop() {
        if (!running) return;
        running = false;
        disposeLoops();
        persistNow();
        table.setAvailable(false);
        state = State.LEFT;
        try {
            cluster.get().close();
        } finally {
            clearPeerMetrics();
            table.clearPeers();
        }
    }

    private void disposeLoops() {
        if (information != null) information.deactivate();
        for (Disposable d : new Disposable[]{pings, events, persister, refresher}) {
            if (d != null) {
                d.dispose();
            }
        }
    }

    /** Writes the state file from another thread; for callers (the admin API) that run on the event loop. */
    public void requestPersist() {
        Schedulers.boundedElastic().schedule(this::persistNow);
    }

    public void informationSettingsChanged() {
        NodeInformationExchange exchange = information;
        if (exchange != null) exchange.settingsChanged();
    }

    public NodeInformation localInformation() {
        return information.local();
    }

    public java.util.Optional<NodeInformationExchange.RemoteInformation> peerInformation(String id) {
        return information.remote(id);
    }

    public void persistNow() {
        P2pStateFile f = stateFile.get();
        if (f == null || nodeId == null) {
            return;
        }
        // the snapshot is taken and written under one lock: an older snapshot can never land after a newer one
        synchronized (persistLock) {
            try {
                Map<String, KnownPeer> known = new HashMap<>();
                if (System.nanoTime() - startedNanos < hintTtl.toNanos()) {
                    known.putAll(hints);   // not confirmed yet: kept for the next restart, but only for a grace period
                }
                table.snapshot().forEach(p -> known.put(p.id(), new KnownPeer(p.id(), p.address(), p.rttMs(), p.coord())));
                f.saveState(new P2pFile.State(nodeId, vivaldi.coord().toList(), vivaldi.error(),
                        settings.overrides(), table.apiModes(), List.copyOf(known.values())));
                persistFailing = false;
            } catch (RuntimeException e) {
                reportPersistFailure(e);
            }
        }
    }

    public PeerMessaging messaging() {
        PeerMessaging m = messaging.get();
        if (!running || state != State.ACTIVE || m == null) {
            throw new IllegalStateException("p2p-discovery is not running");
        }
        return m;
    }

    private void reportPersistFailure(RuntimeException e) {
        if (persistFailing) {
            log.debug("p2p state still cannot be persisted: {}", e.getMessage());   // already warned; stay quiet
        } else {
            persistFailing = true;
            log.warn("could not persist p2p state (further failures are logged at debug): {}", e.getMessage());
        }
    }

    public String address() {
        PeerCluster c = cluster.get();
        if (c == null || state == State.LEFT) {
            throw new IllegalStateException("p2p-discovery is not running");
        }
        return c.address();
    }

    PeerTable tableForTest() {
        return table;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void registerAggregateMetrics() {
        Gauge.builder("p2p_peers", table, t -> t.snapshot().size()).register(meters);
        Gauge.builder("p2p_peers_active", table, t -> t.active().size()).register(meters);
    }
}
