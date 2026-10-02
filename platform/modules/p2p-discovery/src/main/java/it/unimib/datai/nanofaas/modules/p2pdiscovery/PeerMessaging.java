package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

/** Send/receive primitives restricted to active neighbors. Application payloads are opaque bytes. */
public final class PeerMessaging {
    static final String RESERVED_PREFIX = "p2p.";

    /** Seam over {@link PeerCluster} so messaging and latency logic are testable without sockets. */
    public interface Wire {
        Mono<Void> send(String address, String topic, byte[] payload);

        Mono<byte[]> request(String address, String topic, byte[] payload, Duration timeout);

        void handle(String topic, PeerCluster.Handler handler);
    }

    @FunctionalInterface
    public interface Receiver {
        Mono<byte[]> onMessage(String senderId, byte[] payload);
    }

    public static final class PeerNotActiveException extends RuntimeException {
        public PeerNotActiveException(String peerId) {
            super("peer is not an active neighbor: " + peerId);
        }
    }

    public static Wire wire(PeerCluster c) {
        return new Wire() {
            public Mono<Void> send(String a, String t, byte[] p) { return c.send(a, t, p); }
            public Mono<byte[]> request(String a, String t, byte[] p, Duration d) { return c.request(a, t, p, d); }
            public void handle(String t, PeerCluster.Handler h) { c.handle(t, h); }
        };
    }

    private final Wire wire;
    private final PeerTable table;

    public PeerMessaging(Wire wire, PeerTable table) {
        this.wire = wire;
        this.table = table;
    }

    public Mono<Void> send(String peerId, String topic, byte[] payload) {
        return Mono.defer(() -> {
            checkTopic(topic);
            return activeAddress(peerId).flatMap(a -> wire.send(a, topic, payload));
        });
    }

    public Mono<byte[]> request(String peerId, String topic, byte[] payload, Duration timeout) {
        return Mono.defer(() -> {
            checkTopic(topic);
            return activeAddress(peerId).flatMap(a -> wire.request(a, topic, payload, timeout));
        });
    }

    /** Emits the number of active peers the message was sent to; the peers are chosen when subscribed. */
    public Mono<Integer> broadcast(String topic, byte[] payload) {
        return Mono.defer(() -> {
            checkTopic(topic);
            var targets = table.active();
            return Flux.fromIterable(targets)
                    .flatMap(p -> wire.send(p.address(), topic, payload).onErrorResume(e -> Mono.empty()))
                    .then(Mono.just(targets.size()));
        });
    }

    public void subscribe(String topic, Receiver receiver) {
        checkTopic(topic);
        wire.handle(topic, (senderAddress, payload) -> {
            String id = table.idOf(senderAddress).orElse(null);
            if (id == null || !table.isActive(id)) {
                return Mono.error(new PeerCluster.Dropped());   // not an active neighbor: no reply, nothing logged
            }
            return receiver.onMessage(id, payload);
        });
    }

    private Mono<String> activeAddress(String peerId) {
        return Mono.defer(() -> table.activeAddressOf(peerId)
                .map(Mono::just)
                .orElseGet(() -> Mono.error(new PeerNotActiveException(peerId))));
    }

    private static void checkTopic(String topic) {
        if (topic == null || topic.isBlank() || topic.startsWith(RESERVED_PREFIX)) {
            throw new IllegalArgumentException("invalid or reserved topic: " + topic);
        }
    }
}
