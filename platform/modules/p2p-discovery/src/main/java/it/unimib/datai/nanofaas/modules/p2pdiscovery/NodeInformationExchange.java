package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import static it.unimib.datai.nanofaas.modules.p2pdiscovery.NodeInformation.*;

/** Bounded collection and direct-neighbor polling; handlers only serve cached observations. */
public final class NodeInformationExchange implements AutoCloseable {
    public static final String TOPIC = "nanofaas.node-info.v1";
    private static final Duration DEADLINE = Duration.ofSeconds(2);
    private static final long TTL_MILLIS = 15_000;
    public record RemoteInformation(String nodeId, String state, NodeInformation information) {}
    private record Sample(Category<?> category, long nanos) {}
    private record Received(NodeInformation information, long nanos, long generation) {}
    private static final class Slot {
        final AtomicBoolean busy = new AtomicBoolean();
        Sample sample;
        Thread worker;
        long version;
    }
    private final String nodeId;
    private final P2pSettings settings;
    private final PeerTable table;
    private final NodeInformationCollector collector;
    private final LongSupplier nanos;
    private final NodeInformationCodec codec = new NodeInformationCodec();
    private P2pSettings.Sharing previousSharing;
    private final Slot[] slots = {new Slot(), new Slot(), new Slot()};
    private final Map<String, Received> received = new HashMap<>();
    private ScheduledExecutorService scheduler = newScheduler();

    private static ScheduledExecutorService newScheduler() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("p2p-information").factory());
    }
    private PeerMessaging messaging;
    private boolean registered;
    private boolean active;
    private boolean polling;
    private long epoch;
    private ScheduledFuture<?> loop;
    private Disposable round;

    public NodeInformationExchange(String nodeId, P2pSettings settings, PeerTable table,
                                   NodeInformationCollector collector, LongSupplier nanos) {
        this.nodeId = nodeId; this.settings = settings; this.table = table;
        this.collector = collector; this.nanos = nanos;
        previousSharing = settings.sharing();
    }

    public synchronized void activate(PeerMessaging messaging, boolean schedule) {
        this.messaging = messaging;
        if (!registered) {
            messaging.subscribe(TOPIC, (sender, payload) -> Mono.defer(() -> {
                synchronized (this) {
                    if (!active || payload.length != 0) return Mono.error(new PeerCluster.Dropped());
                    return Mono.just(codec.encode(local()));
                }
            }));
            registered = true;
        }
        if (active) return;
        if (scheduler.isShutdown()) scheduler = newScheduler();
        active = true;
        epoch++;
        if (schedule) loop = scheduler.scheduleWithFixedDelay(this::tick, 0, 5, TimeUnit.SECONDS);
    }

    public synchronized void deactivate() {
        active = false;
        epoch++;
        if (loop != null) loop.cancel(false);
        if (round != null) round.dispose();
        polling = false;
        received.clear();
        for (Slot slot : slots) {
            slot.sample = null;
            if (slot.worker != null) slot.worker.interrupt();
        }
        scheduler.shutdownNow();
    }

    /** Only categories whose sharing flag changed lose their observations and running results. */
    public synchronized void settingsChanged() {
        P2pSettings.Sharing current = settings.sharing();
        for (int index = 0; index < slots.length; index++) {
            if (enabled(previousSharing, index) == enabled(current, index)) continue;
            Slot slot = slots[index];
            slot.version++;
            slot.sample = null;
            if (slot.worker != null) slot.worker.interrupt();
            if (active && enabled(current, index)) {
                int changedIndex = index;
                scheduler.execute(() -> {
                    synchronized (this) {
                        if (active && enabled(changedIndex)) start(changedIndex, operation(changedIndex));
                    }
                });
            }
        }
        previousSharing = current;
    }

    private void tick() {
        collect();
        poll();
    }

    synchronized void collect() {
        if (!active) return;
        for (int index = 0; index < slots.length; index++) start(index, operation(index));
    }

    private Supplier<Category<?>> operation(int index) {
        return switch (index) {
            case 0 -> collector::collectFunctions;
            case 1 -> () -> collector.collectImages(DEADLINE);
            default -> collector::collectResources;
        };
    }

    private void start(int index, Supplier<Category<?>> operation) {
        Slot slot = slots[index];
        if (!enabled(index) || !slot.busy.compareAndSet(false, true)) return;
        long version = epoch;
        long categoryVersion = slot.version;
        long started = nanos.getAsLong();
        var timedOut = new AtomicBoolean();
        // The guard is released only when the real backend call exits, even if it ignores interruption.
        Thread worker = Thread.ofVirtual().unstarted(() -> {
            try {
                Category<?> result;
                try { result = codec.bounded(operation.get()); }
                catch (RuntimeException e) { result = Category.unavailable("COLLECTION_ERROR"); }
                synchronized (this) {
                    if (active && epoch == version && slot.version == categoryVersion && enabled(index) && !timedOut.get()) {
                        slot.sample = new Sample(result, started);
                    }
                }
            } finally {
                slot.busy.set(false);
            }
        });
        slot.worker = worker;
        scheduler.schedule(() -> {
            synchronized (this) {
                if (!worker.isAlive()) return;
                timedOut.set(true);
                if (active && epoch == version && slot.version == categoryVersion && enabled(index)) {
                    slot.sample = new Sample(Category.unavailable("TIMEOUT"), nanos.getAsLong());
                }
                worker.interrupt();
            }
        }, DEADLINE.toNanos(), TimeUnit.NANOSECONDS);
        worker.start();
        // No timeout cancellation is needed for correctness; completed calls make the task a no-op.
    }

    private boolean enabled(int index) {
        return enabled(settings.sharing(), index);
    }

    private static boolean enabled(P2pSettings.Sharing sharing, int index) {
        return switch (index) { case 0 -> sharing.functions(); case 1 -> sharing.images(); default -> sharing.resources(); };
    }

    public synchronized NodeInformation local() {
        return new NodeInformation(1, nodeId, Instant.now(), category(0), category(1), category(2));
    }

    @SuppressWarnings("unchecked")
    private <T> Category<T> category(int index) {
        if (!enabled(index)) return Category.disabled();
        if (!active) return Category.unavailable("INACTIVE");
        Sample sample = slots[index].sample;
        if (sample == null) return Category.unavailable("NOT_COLLECTED");
        long age = elapsed(sample.nanos());
        return (Category<T>) age(sample.category(), age);
    }

    private Category<?> age(Category<?> category, long elapsed) {
        if (category.status() == Status.DISABLED || category.status() == Status.UNAVAILABLE) return category;
        long age = category.ageMillis() >= TTL_MILLIS || elapsed >= TTL_MILLIS
                ? TTL_MILLIS : category.ageMillis() + elapsed;
        return category.aged(age);
    }

    private long elapsed(long started) { return Math.max(0, TimeUnit.NANOSECONDS.toMillis(nanos.getAsLong() - started)); }

    synchronized void poll() {
        if (!active || polling) return;
        prune();
        polling = true;
        long version = epoch;
        var targets = table.active();
        // Deferred subscriptions in this round retain the transport acquired under the monitor.
        PeerMessaging transport = messaging;
        round = Flux.fromIterable(targets).flatMap(peer -> {
            long generation = table.activationGeneration(peer.id());
            return transport.request(peer.id(), TOPIC, new byte[0], DEADLINE).timeout(DEADLINE)
                    .doOnNext(bytes -> accept(peer.id(), generation, version, bytes))
                    .onErrorResume(e -> Mono.empty());
        }, 4).doFinally(signal -> {
            synchronized (this) { if (epoch == version) polling = false; }
        }).subscribe();
    }

    private synchronized void accept(String id, long generation, long version, byte[] payload) {
        if (!active || epoch != version || generation < 0 || table.activationGeneration(id) != generation) return;
        try { received.put(id, new Received(codec.decode(payload, id), nanos.getAsLong(), generation)); }
        catch (RuntimeException ignored) { /* malformed or legacy response does not replace the last valid snapshot */ }
    }

    private void prune() {
        received.entrySet().removeIf(e -> table.activationGeneration(e.getKey()) != e.getValue().generation());
    }

    public synchronized Optional<RemoteInformation> remote(String id) {
        if (table.addressOf(id).isEmpty()) return Optional.empty();
        prune();
        if (!active || !table.isActive(id)) return Optional.of(new RemoteInformation(id, "INACTIVE", null));
        Received value = received.get(id);
        if (value == null) return Optional.of(new RemoteInformation(id, "NOT_RECEIVED", null));
        long elapsed = elapsed(value.nanos());
        if (elapsed >= TTL_MILLIS) return Optional.of(new RemoteInformation(id, "STALE", null));
        NodeInformation info = value.information();
        return Optional.of(new RemoteInformation(id, "CURRENT", new NodeInformation(1, id, info.sampledAt(),
                cast(age(info.functions(), elapsed)), cast(age(info.images(), elapsed)), cast(age(info.resources(), elapsed)))));
    }

    @SuppressWarnings("unchecked")
    private static <T> Category<T> cast(Category<?> category) { return (Category<T>) category; }
    @Override public synchronized void close() { deactivate(); scheduler.shutdownNow(); }
}
