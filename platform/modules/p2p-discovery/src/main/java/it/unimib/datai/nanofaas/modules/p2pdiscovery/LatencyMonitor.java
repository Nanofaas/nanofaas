package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import it.unimib.datai.nanofaas.modules.p2pdiscovery.Vivaldi.Coord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.function.LongSupplier;

/** One ping round trip per known peer: yields the RTT and the remote Vivaldi coordinate. */
public final class LatencyMonitor {
    private static final Logger log = LoggerFactory.getLogger(LatencyMonitor.class);
    static final String TOPIC = "p2p.ping";
    private static final int PAYLOAD = 4 * Double.BYTES;
    private static final double MAX_COORD = 1e6;   // coordinates are in ms: a million is already absurd

    private final PeerMessaging.Wire wire;
    private final PeerTable table;
    private final Vivaldi vivaldi;
    private final Duration timeout;
    private final LongSupplier nanoClock;

    public LatencyMonitor(PeerMessaging.Wire wire, PeerTable table, Vivaldi vivaldi,
                          Duration timeout, LongSupplier nanoClock) {
        this.wire = wire;
        this.table = table;
        this.vivaldi = vivaldi;
        this.timeout = timeout;
        this.nanoClock = nanoClock;
    }

    /** Installs the responder: any ping is answered with our own coordinate and error. */
    public void register() {
        wire.handle(TOPIC, (sender, payload) -> Mono.fromSupplier(this::encodeLocal));
    }

    /**
     * Pings every {@code interval}. A round slower than the interval must not stop the loop: the interval source
     * fails with an overflow error when its consumer is still busy at the next tick, so late ticks are dropped,
     * and a prefetch of one avoids a burst of catch-up rounds.
     */
    public Disposable schedule(Duration interval) {
        return Flux.interval(interval)
                .onBackpressureDrop()
                .concatMap(i -> pingAll().onErrorResume(e -> Mono.empty()), 1)
                .subscribe(v -> { }, e -> log.error("the ping loop stopped", e));
    }

    /** One round over every known peer (active or not: unmeasured peers must be measurable). Never errors. */
    public Mono<Void> pingAll() {
        return Flux.fromIterable(table.snapshot())
                .flatMap(p -> ping(p.id(), p.address()), 8)
                .then();
    }

    private Mono<Void> ping(String id, String address) {
        return Mono.defer(() -> {
            long start = nanoClock.getAsLong();
            return wire.request(address, TOPIC, encodeLocal(), timeout)
                    .doOnNext(reply -> onPong(id, (nanoClock.getAsLong() - start) / 1e6, reply))
                    .doOnError(e -> {
                        log.debug("ping {} failed: {}", id, e.toString());
                        table.recordTimeout(id, timeout.toMillis());
                    })
                    .onErrorResume(e -> Mono.empty())
                    .then();
        });
    }

    private void onPong(String id, double rttMs, byte[] reply) {
        if (reply.length != PAYLOAD) {
            log.debug("ignoring malformed pong from {} ({} bytes)", id, reply.length);
            return;
        }
        ByteBuffer b = ByteBuffer.wrap(reply);
        Coord remote = new Coord(b.getDouble(), b.getDouble(), b.getDouble());
        double remoteError = b.getDouble();
        if (!isUsable(remote, remoteError)) {
            log.debug("ignoring pong from {} with non-finite coordinates", id);
            return;   // a faulty or hostile peer must not poison our coordinates
        }
        vivaldi.update(rttMs, remote, remoteError);
        table.recordRtt(id, rttMs, remote);
    }

    private static boolean isUsable(Coord c, double error) {
        return Math.abs(c.x()) <= MAX_COORD && Math.abs(c.y()) <= MAX_COORD && Math.abs(c.z()) <= MAX_COORD
                && error >= 0 && error <= 1;   // NaN fails every comparison; the bound keeps distances from overflowing
    }

    private byte[] encodeLocal() {
        Coord c = vivaldi.coord();
        return ByteBuffer.allocate(PAYLOAD).putDouble(c.x()).putDouble(c.y()).putDouble(c.z())
                .putDouble(vivaldi.error()).array();
    }
}
