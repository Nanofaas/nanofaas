package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import it.unimib.datai.nanofaas.p2papi.*;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** Versioned endpoint negotiation over existing P2P messaging. Legacy peers remain discoverable. */
public final class DefaultPeerTransport implements PeerTransport, SmartLifecycle {
    static final String ANNOUNCEMENT_TOPIC = "nanofaas.endpoint.v1";
    private static final int MAX_BYTES = 1 << 20;
    private static final Duration ANNOUNCEMENT_TIMEOUT = Duration.ofSeconds(2);
    public record Session(String nodeId, String incarnation, PeerMessaging messaging) {}
    private record Announcement(PeerEndpoint endpoint, long generation, long receivedNanos) {}
    private static final class Registration {
        final PeerReceiver receiver;
        boolean closed;
        Registration(PeerReceiver receiver) { this.receiver = receiver; }
    }
    private final PeerTable table;
    private final Supplier<Session> sessions;
    private final URI invocationUri;
    private final Semaphore permits;
    private final int concurrency;
    private final Map<String, Announcement> endpoints = new HashMap<>();
    private final Map<String, Registration> registrations = new HashMap<>();
    private Session bound;
    private Disposable loop;
    private Disposable round;
    private volatile boolean stopped;
    private boolean polling;
    private volatile boolean running;

    public DefaultPeerTransport(PeerTable table, Supplier<Session> sessions, URI invocationUri, int concurrency) {
        if (concurrency < 1) throw new IllegalArgumentException("positive concurrency required");
        if (invocationUri != null) new PeerEndpoint("validation", "validation", invocationUri);
        this.table = table; this.sessions = sessions; this.invocationUri = invocationUri;
        this.concurrency = concurrency; this.permits = new Semaphore(concurrency);
    }

    private Session current() {
        Session session = sessions.get();
        if (stopped || session == null) throw new IllegalStateException("P2P transport is inactive");
        return session;
    }
    private boolean sameSession(Session expected) {
        Session current = sessions.get();
        return !stopped && current != null && current.incarnation().equals(expected.incarnation())
                && current.messaging() == expected.messaging();
    }

    @Override public synchronized List<PeerEndpoint> activeNeighbors() {
        if (stopped || sessions.get() == null) return List.of();
        endpoints.entrySet().removeIf(e -> e.getValue().generation() != table.activationGeneration(e.getKey())
                || System.nanoTime() - e.getValue().receivedNanos() >= Duration.ofSeconds(15).toNanos());
        return endpoints.values().stream().map(Announcement::endpoint)
                .sorted(Comparator.comparing(PeerEndpoint::peerId)).toList();
    }

    @Override public Mono<byte[]> request(String peerId, String topic, byte[] payload, Duration timeout) {
        return Mono.defer(() -> {
            bounded(payload);
            if (timeout == null || timeout.isZero() || timeout.isNegative())
                return Mono.error(new IllegalArgumentException("positive request timeout required"));
            Session session = current();
            long generation = table.activationGeneration(peerId);
            if (!permits.tryAcquire()) return Mono.error(new IllegalStateException("P2P request limit reached"));
            return Mono.defer(() -> session.messaging().request(peerId, topic, payload.clone(), timeout))
                    .timeout(timeout).map(reply -> {
                        bounded(reply);
                        if (!sameSession(session) || generation < 0 || generation != table.activationGeneration(peerId))
                            throw new IllegalStateException("stale P2P response");
                        return reply.clone();
                    }).doFinally(signal -> permits.release());
        });
    }

    @Override public synchronized PeerSubscription subscribe(String topic, PeerReceiver receiver) {
        if (ANNOUNCEMENT_TOPIC.equals(topic)) throw new IllegalArgumentException("reserved endpoint topic");
        Registration old = registrations.get(topic);
        if (old != null && !old.closed) throw new IllegalStateException("topic already subscribed");
        Registration registration = new Registration(Objects.requireNonNull(receiver));
        Session session = current();
        registrations.put(topic, registration);
        bind(topic, registration, session);
        return () -> { synchronized (this) {
            registration.closed = true;
            registrations.remove(topic, registration);
        } };
    }

    private void bind(String topic, Registration registration, Session session) {
        session.messaging().subscribe(topic, (sender, payload) -> Mono.defer(() -> {
            synchronized (this) {
                if (registration.closed || !sameSession(session)) return Mono.error(new PeerCluster.Dropped());
                bounded(payload);
                return registration.receiver.onMessage(sender, payload.clone()).map(reply -> {
                    bounded(reply); return reply.clone();
                });
            }
        }));
    }

    static byte[] encodeAnnouncement(String nodeId, String incarnation, URI uri) {
        if (nodeId.contains("\n") || incarnation.contains("\n")) throw new IllegalArgumentException("invalid identity");
        return ("1\n" + nodeId + "\n" + incarnation + "\n" + uri).getBytes(StandardCharsets.UTF_8);
    }

    synchronized void acceptAnnouncement(String peerId, long generation, byte[] payload) {
        if (payload.length > 4096 || generation < 0 || table.activationGeneration(peerId) != generation) return;
        try {
            String[] fields = new String(payload, StandardCharsets.UTF_8).split("\n", -1);
            if (fields.length != 4 || !fields[0].equals("1") || !fields[1].equals(peerId)) return;
            PeerEndpoint endpoint = new PeerEndpoint(peerId, fields[2], URI.create(fields[3]));
            endpoints.put(peerId, new Announcement(endpoint, generation, System.nanoTime()));
        } catch (IllegalArgumentException ignored) { /* Legacy or malformed advertisement is ineligible. */ }
    }

    synchronized void refresh() {
        if (stopped) return;
        Session session = sessions.get();
        if (session == null) { endpoints.clear(); bound = null; return; }
        if (bound != null && sameSession(bound)) return;
        endpoints.clear(); bound = session;
        session.messaging().subscribe(ANNOUNCEMENT_TOPIC, (sender, payload) -> Mono.defer(() -> {
            synchronized (this) {
                if (!sameSession(session) || invocationUri == null || payload.length != 0)
                    return Mono.error(new PeerCluster.Dropped());
                return Mono.just(encodeAnnouncement(session.nodeId(), session.incarnation(), invocationUri));
            }
        }));
        registrations.forEach((topic, registration) -> bind(topic, registration, session));
    }

    private synchronized void tick() {
        refresh();
        if (bound == null || polling) return;
        polling = true;
        Session session = bound;
        round = Flux.fromIterable(table.active()).flatMap(peer -> {
            long generation = table.activationGeneration(peer.id());
            return request(peer.id(), ANNOUNCEMENT_TOPIC, new byte[0], ANNOUNCEMENT_TIMEOUT)
                    .doOnNext(bytes -> { synchronized (this) {
                        if (sameSession(session)) acceptAnnouncement(peer.id(), generation, bytes);
                    } }).onErrorResume(error -> Mono.empty());
        }, concurrency).doFinally(signal -> { synchronized (this) { polling = false; } }).subscribe();
    }
    private static void bounded(byte[] payload) {
        if (payload == null || payload.length > MAX_BYTES) throw new IllegalArgumentException("payload exceeds 1 MiB");
    }
    @Override public synchronized void start() {
        if (running) return;
        stopped = false;
        running = true;
        loop = Flux.interval(Duration.ZERO, Duration.ofSeconds(1)).subscribe(i -> tick());
    }
    @Override public synchronized void stop() {
        running = false;
        stopped = true;
        if (round != null) round.dispose();
        if (loop != null) loop.dispose();
        registrations.values().forEach(r -> r.closed = true);
        registrations.clear(); endpoints.clear(); bound = null;
    }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 2047; }
}
