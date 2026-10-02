package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import it.unimib.datai.nanofaas.modules.p2pdiscovery.NeighborSelector.Settings;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.Disposable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class LatencyMonitorTest {
    private final AtomicLong clock = new AtomicLong();
    private final PeerTable table = new PeerTable(() -> new Settings(null, 50.0));
    private final Vivaldi vivaldi = new Vivaldi(1);

    private static byte[] pong() {
        return ByteBuffer.allocate(32).putDouble(10).putDouble(0).putDouble(0).putDouble(0.5).array();
    }

    /** A wire whose request() advances the fake clock by the configured RTT. */
    private PeerMessaging.Wire wire(long rttNanos, boolean fail) {
        return new PeerMessaging.Wire() {
            public Mono<Void> send(String a, String t, byte[] p) { return Mono.empty(); }
            public Mono<byte[]> request(String a, String t, byte[] p, Duration d) {
                return Mono.defer(() -> {
                    clock.addAndGet(rttNanos);
                    return fail ? Mono.error(new RuntimeException("timeout")) : Mono.just(pong());
                });
            }
            public void handle(String t, PeerCluster.Handler h) { /* the responder is not exercised here */ }
        };
    }

    @Test
    void pingRecordsRttAndActivatesAFastPeer() {
        table.upsert("a", "a:1");
        new LatencyMonitor(wire(20_000_000L, false), table, vivaldi, Duration.ofSeconds(1), clock::get).pingAll().block();
        assertThat(table.snapshot().getFirst().rttMs()).isCloseTo(20.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(table.isActive("a")).isTrue();
    }

    @Test
    void slowPeerStaysOutUnderTheThreshold() {
        table.upsert("a", "a:1");
        new LatencyMonitor(wire(90_000_000L, false), table, vivaldi, Duration.ofSeconds(1), clock::get).pingAll().block();
        assertThat(table.isActive("a")).isFalse();
    }

    @Test
    void failedPingIsSwallowedAndCountsAsASampleAtTheTimeout() {
        table.upsert("a", "a:1");
        new LatencyMonitor(wire(1, true), table, vivaldi, Duration.ofSeconds(1), clock::get).pingAll().block();
        assertThat(table.snapshot().getFirst().rttMs()).isEqualTo(1000.0);   // the 1 s timeout, not a frozen old value
    }

    @Test
    void pingHandlerRepliesWithLocalCoordinateAndError() {
        var captured = new PeerCluster.Handler[1];
        PeerMessaging.Wire w = new PeerMessaging.Wire() {
            public Mono<Void> send(String a, String t, byte[] p) { return Mono.empty(); }
            public Mono<byte[]> request(String a, String t, byte[] p, Duration d) { return Mono.empty(); }
            public void handle(String t, PeerCluster.Handler h) { assertThat(t).isEqualTo(LatencyMonitor.TOPIC); captured[0] = h; }
        };
        new LatencyMonitor(w, table, vivaldi, Duration.ofSeconds(1), clock::get).register();
        ByteBuffer reply = ByteBuffer.wrap(captured[0].onMessage("x:1", pong()).block());
        assertThat(reply.remaining()).isEqualTo(32);
        reply.getDouble(); reply.getDouble(); reply.getDouble();
        assertThat(reply.getDouble()).isEqualTo(vivaldi.error());
    }

    @Test
    void malformedPongIsIgnored() {
        table.upsert("a", "a:1");
        PeerMessaging.Wire w = new PeerMessaging.Wire() {
            public Mono<Void> send(String a, String t, byte[] p) { return Mono.empty(); }
            public Mono<byte[]> request(String a, String t, byte[] p, Duration d) { return Mono.just(new byte[3]); }
            public void handle(String t, PeerCluster.Handler h) { /* the responder is not exercised here */ }
        };
        new LatencyMonitor(w, table, vivaldi, Duration.ofSeconds(1), clock::get).pingAll().block();
        assertThat(table.snapshot().getFirst().rttMs()).isNull();
    }

    @Test
    void pongWithNonFiniteValuesDoesNotPoisonTheCoordinates() {
        table.upsert("a", "a:1");
        byte[] poisoned = ByteBuffer.allocate(32).putDouble(Double.NaN).putDouble(0).putDouble(0)
                .putDouble(Double.POSITIVE_INFINITY).array();
        PeerMessaging.Wire w = new PeerMessaging.Wire() {
            public Mono<Void> send(String a, String t, byte[] p) { return Mono.empty(); }
            public Mono<byte[]> request(String a, String t, byte[] p, Duration d) { return Mono.just(poisoned); }
            public void handle(String t, PeerCluster.Handler h) { /* the responder is not exercised here */ }
        };
        new LatencyMonitor(w, table, vivaldi, Duration.ofSeconds(1), clock::get).pingAll().block();
        assertThat(table.snapshot().getFirst().rttMs()).isNull();
        assertThat(Double.isFinite(vivaldi.coord().x())).isTrue();
        assertThat(Double.isFinite(vivaldi.error())).isTrue();
    }

    @Test
    void theScheduleSurvivesARoundSlowerThanItsInterval() {
        table.upsert("slow", "s:1");
        table.upsert("fast", "f:1");
        AtomicInteger fastPings = new AtomicInteger();
        Duration timeout = Duration.ofMillis(300);
        PeerMessaging.Wire w = new PeerMessaging.Wire() {
            public Mono<Void> send(String a, String t, byte[] p) { return Mono.empty(); }
            public Mono<byte[]> request(String a, String t, byte[] p, Duration d) {
                if (a.equals("f:1")) {
                    fastPings.incrementAndGet();
                    return Mono.just(pong());
                }
                return Mono.<byte[]>never().timeout(d);   // a peer that never answers: every round lasts a full timeout
            }
            public void handle(String t, PeerCluster.Handler h) { /* the responder is not exercised here */ }
        };
        Disposable loop = new LatencyMonitor(w, table, vivaldi, timeout, System::nanoTime).schedule(Duration.ofMillis(50));
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> fastPings.get() >= 4);
            assertThat(loop.isDisposed()).isFalse();
        } finally {
            loop.dispose();
        }
    }

    @Test
    void repeatedTimeoutsAgeThePeerOutOfTheThresholdInsteadOfFreezingItsLastRtt() {
        table.upsert("a", "a:1");
        new LatencyMonitor(wire(20_000_000L, false), table, vivaldi, Duration.ofSeconds(1), clock::get).pingAll().block();
        assertThat(table.isActive("a")).isTrue();
        LatencyMonitor failing = new LatencyMonitor(wire(1, true), table, vivaldi, Duration.ofSeconds(1), clock::get);
        for (int i = 0; i < 6; i++) {
            failing.pingAll().block();
        }
        assertThat(table.isActive("a")).isFalse();   // the median is now the timeout, far above the 50 ms threshold
    }

    @Test
    void hugeFiniteCoordinatesDoNotTurnOurOwnIntoNaN() {
        table.upsert("a", "a:1");
        byte[] huge = ByteBuffer.allocate(32).putDouble(1e300).putDouble(1e300).putDouble(1e300).putDouble(0.5).array();
        PeerMessaging.Wire w = new PeerMessaging.Wire() {
            public Mono<Void> send(String a, String t, byte[] p) { return Mono.empty(); }
            public Mono<byte[]> request(String a, String t, byte[] p, Duration d) { return Mono.just(huge); }
            public void handle(String t, PeerCluster.Handler h) { /* the responder is not exercised here */ }
        };
        new LatencyMonitor(w, table, vivaldi, Duration.ofSeconds(1), clock::get).pingAll().block();
        assertThat(Double.isFinite(vivaldi.coord().x())).isTrue();
        assertThat(table.snapshot().getFirst().rttMs()).isNull();   // the sample is rejected as a whole
    }
}
