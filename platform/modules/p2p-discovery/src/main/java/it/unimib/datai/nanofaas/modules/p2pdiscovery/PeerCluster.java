package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import io.scalecube.cluster.Cluster;
import io.scalecube.cluster.ClusterImpl;
import io.scalecube.cluster.ClusterMessageHandler;
import io.scalecube.cluster.membership.MembershipEvent;
import io.scalecube.cluster.transport.api.Message;
import io.scalecube.cluster.transport.api.Transport;
import io.scalecube.transport.netty.tcp.TcpTransportFactory;
import io.netty.channel.EventLoopGroup;
import reactor.netty.resources.LoopResources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** The only class that knows scalecube. Everything above sees ids, addresses, topics and byte[]. */
public final class PeerCluster implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PeerCluster.class);
    private static final String Q_MSG = "p2p.msg";
    private static final String Q_REQ = "p2p.req";
    private static final String Q_RES = "p2p.res";
    private static final String H_TOPIC = "topic";

    public enum Type { ADDED, REMOVED }

    public record MemberEvent(Type type, String id, String address) {}

    /**
     * What a receive handler signals when it refuses a message on purpose: the sender gets no reply (a request
     * times out) instead of a successful empty one, and nothing is logged.
     */
    public static final class Dropped extends RuntimeException {
        public Dropped() {
            super("message dropped", null, false, false);
        }
    }

    @FunctionalInterface
    public interface Handler {
        Mono<byte[]> onMessage(String senderAddress, byte[] payload);
    }

    private final String nodeId;
    private volatile int port;
    private volatile boolean isolated;
    private final AtomicReference<EventLoopGroup> serverLoops = new AtomicReference<>();
    private final Set<String> knownAddresses = ConcurrentHashMap.newKeySet();
    private final String externalHost;
    private final List<String> seeds;
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private final Sinks.Many<MemberEvent> events = Sinks.many().multicast().directBestEffort();
    private final AtomicReference<Transport> transport = new AtomicReference<>();
    /** Members that announced they are leaving: gone for our callers, though scalecube keeps them until REMOVED. */
    private final Set<String> leaving = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Cluster> cluster = new AtomicReference<>();

    public PeerCluster(String nodeId, int port, String externalHost, List<String> seeds) {
        this.nodeId = nodeId;
        this.port = port;
        this.externalHost = externalHost;
        this.seeds = List.copyOf(seeds);
    }

    public Mono<Void> start() {
        leaving.clear();
        Set<String> joinSeeds = new LinkedHashSet<>(seeds);
        joinSeeds.addAll(knownAddresses);
        return new ClusterImpl()
                .config(c -> {
                    c = c.memberId(nodeId).memberAlias(nodeId);
                    return externalHost == null ? c : c.externalHost(externalHost);
                })
                .transport(t -> t.port(port).messageCodec(new PeerClusterCodec()).transportFactory(cfg -> {
                    Transport delegate = new TcpTransportFactory().createTransport(cfg);
                    // 2.7.1 signals stop before its loops terminate on Reactor Netty 1.3.6.
                    // Capture the real group now, so close waits for selector deregistration too.
                    // Its connection cache also retains failed writes; evict them so a rejoining
                    // node at the same address can receive the next protocol exchange.
                    final Map<?, ?> connections;
                    try {
                        var cache = delegate.getClass().getDeclaredField("connections");
                        cache.setAccessible(true);
                        connections = (Map<?, ?>) cache.get(delegate);
                        var field = delegate.getClass().getDeclaredField("loopResources");
                        field.setAccessible(true);
                        var loops = (LoopResources) field.get(delegate);
                        serverLoops.set(loops.onServer(LoopResources.DEFAULT_NATIVE));
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException("cannot capture scalecube transport loops", e);
                    }
                    Transport tr = new Transport() {
                        public String address() { return delegate.address(); }
                        public Mono<Transport> start() { return delegate.start().thenReturn(this); }
                        public Mono<Void> stop() { return delegate.stop(); }
                        public boolean isStopped() { return delegate.isStopped(); }
                        public Mono<Void> send(String address, Message message) {
                            return Mono.defer(() -> isolated ? Mono.empty() : delegate.send(address, message)
                                    .doOnError(e -> connections.remove(address)));
                        }
                        public Mono<Message> requestResponse(String address, Message message) {
                            return Mono.defer(() -> isolated ? Mono.never()
                                    : delegate.requestResponse(address, message).filter(m -> !isolated)
                                    .doOnError(e -> connections.remove(address)));
                        }
                        public Flux<Message> listen() { return delegate.listen().filter(m -> !isolated); }
                    };
                    transport.set(tr);
                    return tr;
                }))
                .membership(m -> m.seedMembers(List.copyOf(joinSeeds)))
                .handler(c -> new ClusterMessageHandler() {
                    @Override
                    public void onMessage(Message m) {
                        dispatch(m);
                    }

                    @Override
                    public void onMembershipEvent(MembershipEvent e) {
                        onMembership(e);
                    }
                })
                .start()
                .doOnNext(c -> {
                    cluster.set(c);
                    port = Transport.parsePort(transport.get().address());
                })
                .onErrorResume(e -> Mono.fromRunnable(this::close)
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).then(Mono.error(e)))
                .then();
    }

    public String id() {
        return nodeId;
    }

    public String address() {
        return cluster.get().address();
    }

    public Flux<MemberEvent> events() {
        return events.asFlux();
    }

    public List<MemberEvent> members() {
        return cluster.get().otherMembers().stream()
                .filter(m -> !leaving.contains(m.id()))
                .map(m -> new MemberEvent(Type.ADDED, m.id(), m.address())).toList();
    }

    public void handle(String topic, Handler handler) {
        if (handlers.putIfAbsent(topic, handler) != null) {
            throw new IllegalStateException("handler already registered for topic " + topic);
        }
    }

    public void unhandle(String topic, Handler handler) { handlers.remove(topic, handler); }

    public Mono<Void> send(String address, String topic, byte[] payload) {
        return Mono.defer(() -> cluster.get() == null || isolated
                ? Mono.error(new IllegalStateException("p2p node is unavailable"))
                : transport.get().send(address, message(Q_MSG, topic, null, payload)));
    }

    public Mono<byte[]> request(String address, String topic, byte[] payload, Duration timeout) {
        return Mono.defer(() -> {
            if (cluster.get() == null || isolated) {
                return Mono.error(new IllegalStateException("p2p node is unavailable"));
            }
            String cid = UUID.randomUUID().toString();
            return transport.get().requestResponse(address, message(Q_REQ, topic, cid, payload))
                    .timeout(timeout).map(m -> (byte[]) m.data());
        });
    }

    public void setIsolated(boolean isolated) {
        this.isolated = isolated;
    }

    @Override
    public void close() {
        Cluster c = cluster.getAndSet(null);
        try {
            if (c != null) {
                c.shutdown();
                c.onShutdown().block(Duration.ofSeconds(5));
            } else {
                Transport tr = transport.get();
                if (tr != null && !tr.isStopped()) tr.stop().block(Duration.ofSeconds(5));
            }
        } finally {
            EventLoopGroup loops = serverLoops.getAndSet(null);
            if (loops != null) {
                // Also covers a bind that failed before scalecube could install its stop subscription.
                loops.shutdownGracefully();
                if (!loops.terminationFuture().awaitUninterruptibly(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("p2p transport loops did not terminate");
                }
            }
            transport.set(null);
        }
    }

    private void onMembership(MembershipEvent e) {
        String id = e.member().id();
        String address = e.member().address();
        MembershipEvent.Type type = e.type();
        if (type == MembershipEvent.Type.ADDED) {
            knownAddresses.add(address);
            leaving.remove(id);
            events.tryEmitNext(new MemberEvent(Type.ADDED, id, address));
        } else if (type == MembershipEvent.Type.LEAVING && leaving.add(id)) {
            events.tryEmitNext(new MemberEvent(Type.REMOVED, id, address));
        } else if (type == MembershipEvent.Type.REMOVED && !leaving.remove(id)) {
            // not already reported when it started leaving
            events.tryEmitNext(new MemberEvent(Type.REMOVED, id, address));
        }
        // UPDATED (metadata change) adds or removes nobody
    }

    private Message message(String qualifier, String topic, String correlationId, byte[] payload) {
        Message.Builder b = Message.withQualifier(qualifier)
                .header(H_TOPIC, topic)
                .sender(address())
                .data(payload);
        if (correlationId != null) {
            b.correlationId(correlationId);
        }
        return b.build();
    }

    /** Runs on scalecube's receive subscription: anything thrown here would cancel it and deafen the node for good. */
    private void dispatch(Message m) {
        try {
            route(m);
        } catch (RuntimeException e) {
            log.warn("dropping a malformed p2p message: {}", e.toString());
        }
    }

    private void route(Message m) {
        String q = m.qualifier();
        if (Q_RES.equals(q) || (!Q_MSG.equals(q) && !Q_REQ.equals(q))) {
            return;   // replies are consumed by Transport.requestResponse; the rest is not ours
        }
        String topic = m.header(H_TOPIC);
        String sender = m.sender();
        Object data = m.data();
        if (topic == null || sender == null || !(data == null || data instanceof byte[])) {
            log.debug("dropping a p2p message without topic or sender, or with a payload that is not bytes");
            return;
        }
        Handler h = handlers.get(topic);
        if (h == null) {
            log.debug("no handler for topic {}", topic);
            return;
        }
        byte[] in = data == null ? new byte[0] : (byte[]) data;
        Mono<byte[]> out = Mono.defer(() -> h.onMessage(sender, in));   // a handler that throws becomes an error signal
        if (Q_REQ.equals(q)) {
            out.defaultIfEmpty(new byte[0])
                    .flatMap(reply -> transport.get().send(sender, Message.withQualifier(Q_RES)
                            .correlationId(m.correlationId()).sender(address()).data(reply).build()))
                    .doOnError(e -> logFailure("reply to " + sender, e))
                    .onErrorResume(e -> Mono.empty())
                    .subscribe();
        } else {
            out.doOnError(e -> logFailure("handler for " + topic, e))
                    .onErrorResume(e -> Mono.empty())
                    .subscribe();
        }
    }

    private static void logFailure(String what, Throwable e) {
        if (!(e instanceof Dropped)) {
            log.warn("{} failed: {}", what, e.getMessage());
        }
    }
}
