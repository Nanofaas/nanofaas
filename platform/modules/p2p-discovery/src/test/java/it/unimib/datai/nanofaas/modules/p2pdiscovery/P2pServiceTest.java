package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import org.slf4j.LoggerFactory;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class P2pServiceTest {
    @TempDir Path dir;

    private static P2pProperties props(boolean enabled, String stateFile, String nodeId, java.util.List<String> seeds) {
        return new P2pProperties(enabled, nodeId, 0, "127.0.0.1", seeds, null, null,
                Duration.ofMillis(200), Duration.ofSeconds(1), stateFile, null);
    }

    private static P2pService service(P2pProperties p) {
        P2pSettings s = new P2pSettings(p.maxNeighbors(), p.maxLatencyMs());
        return new P2pService(p, new PeerTable(s::effective), s, new SimpleMeterRegistry());
    }

    private static P2pService service(P2pProperties p, SimpleMeterRegistry meters, Duration persistEvery, Duration hintTtl) {
        P2pSettings s = new P2pSettings(p.maxNeighbors(), p.maxLatencyMs());
        return new P2pService(p, new PeerTable(s::effective), s, meters, persistEvery, hintTtl);
    }

    @Test
    void disabledServiceDoesNothing() {
        P2pService svc = service(props(false, null, null, java.util.List.of()));
        svc.start();
        assertThat(svc.isRunning()).isFalse();
        assertThatThrownBy(svc::messaging).isInstanceOf(IllegalStateException.class);
        svc.stop();
    }

    @Test
    void generatedNodeIdIsPersistedAndReusedAfterRestart() {
        String file = dir.resolve("p2p.yaml").toString();
        P2pService first = service(props(true, file, null, java.util.List.of()));
        first.start();
        String id;
        try {
            id = new P2pStateFile(Path.of(file)).load().state().nodeId();
            assertThat(id).isNotBlank();
        } finally {
            first.stop();
        }

        P2pService second = service(props(true, file, null, java.util.List.of()));
        second.start();
        try {
            assertThat(new P2pStateFile(Path.of(file)).load().state().nodeId()).isEqualTo(id);
        } finally {
            second.stop();
        }
    }

    @Test
    void corruptStateFileDoesNotStopBoot() throws Exception {
        Path f = dir.resolve("p2p.yaml");
        Files.writeString(f, "{{{{ not yaml");
        P2pService svc = service(props(true, f.toString(), "n1", java.util.List.of()));
        svc.start();
        assertThat(svc.isRunning()).isTrue();
        svc.stop();
    }

    @Test
    void fileConfigBeatsApplicationYmlAndOverridesBeatFileConfig() throws Exception {
        Path f = dir.resolve("p2p.yaml");
        Files.writeString(f, """
                config:
                  maxNeighbors: 3
                state:
                  overrides: {maxNeighbors: 7}
                """);
        P2pProperties p = new P2pProperties(true, "n1", 0, "127.0.0.1", null, 1, null,
                Duration.ofMillis(200), Duration.ofSeconds(1), f.toString(), null);
        P2pSettings s = new P2pSettings(p.maxNeighbors(), p.maxLatencyMs());
        P2pService svc = new P2pService(p, new PeerTable(s::effective), s, new SimpleMeterRegistry());
        svc.start();
        assertThat(s.effective().maxNeighbors()).isEqualTo(7);   // state override > file config > yml (1)
        s.clearOverrides();
        assertThat(s.effective().maxNeighbors()).isEqualTo(3);   // file config > yml
        svc.stop();
    }

    @Test
    void savedPeerThatNeverComesBackIsNotInTheTableButSurvivesInTheFile() throws Exception {
        Path f = dir.resolve("p2p.yaml");
        Files.writeString(f, """
                state:
                  nodeId: n1
                  peers:
                    - {id: ghost, address: "127.0.0.1:1", rttMs: 5.0}
                """);
        P2pService svc = service(props(true, f.toString(), "n1", java.util.List.of()));
        svc.start();
        assertThat(svc.tableForTest().snapshot()).isEmpty();        // unconfirmed hint is not a peer
        svc.persistNow();
        svc.stop();
        assertThat(new P2pStateFile(f).load().state().peers()).extracting(P2pFile.KnownPeer::id).contains("ghost");
    }

    @Test
    void secondNodeJoinsViaSeedAndKnownPeersArePersistedForRestart() {
        P2pService a = service(props(true, null, "a", java.util.List.of()));
        a.start();
        String seed = a.address();
        String file = dir.resolve("b.yaml").toString();
        P2pService b = service(props(true, file, "b", java.util.List.of(seed)));
        b.start();
        await().atMost(Duration.ofSeconds(10)).until(() -> b.tableForTest().snapshot().size() == 1);
        b.persistNow();
        assertThat(new P2pStateFile(Path.of(file)).load().state().peers())
                .extracting(P2pFile.KnownPeer::id).containsExactly("a");
        b.stop();
        a.stop();
    }

    @Test
    void theStateFileIsNotRewrittenWhileNothingChanges() throws Exception {
        Path f = dir.resolve("p2p.yaml");
        P2pService svc = service(props(true, f.toString(), "n1", java.util.List.of()), new SimpleMeterRegistry(),
                Duration.ofMillis(100), Duration.ofMinutes(10));
        svc.start();
        svc.persistNow();
        long before = Files.getLastModifiedTime(f).toMillis();
        await().during(Duration.ofMillis(900)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(Files.getLastModifiedTime(f).toMillis()).isEqualTo(before));
        svc.stop();
    }

    @Test
    void unconfirmedSavedPeersAreDroppedOnceTheirGracePeriodIsOver() throws Exception {
        Path f = dir.resolve("p2p.yaml");
        Files.writeString(f, """
                state:
                  nodeId: n1
                  peers:
                    - {id: ghost, address: "127.0.0.1:1", rttMs: 5.0}
                """);
        P2pService svc = service(props(true, f.toString(), "n1", java.util.List.of()), new SimpleMeterRegistry(),
                Duration.ofSeconds(5), Duration.ZERO);
        svc.start();
        svc.persistNow();
        svc.stop();
        assertThat(new P2pStateFile(f).load().state().peers()).isEmpty();
    }

    @Test
    void thePerPeerGaugeDisappearsWhenThePeerLeaves() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        P2pService a = service(props(true, null, "a", java.util.List.of()), meters, Duration.ofSeconds(5), Duration.ofMinutes(10));
        a.start();
        P2pService b = service(props(true, null, "b", java.util.List.of(a.address())));
        b.start();
        await().atMost(Duration.ofSeconds(10)).until(() -> meters.find("p2p_peer_rtt_ms").tag("peer", "b").gauge() != null);
        b.stop();
        await().atMost(Duration.ofSeconds(20)).until(() -> meters.find("p2p_peer_rtt_ms").tag("peer", "b").gauge() == null);
        a.stop();
    }

    @Test
    void invalidConfigInTheStateFileIsIgnoredAndTheYmlValueKept() throws Exception {
        Path f = dir.resolve("p2p.yaml");
        Files.writeString(f, "config:\n  maxNeighbors: -3\n");
        P2pProperties p = new P2pProperties(true, "n1", 0, "127.0.0.1", null, 2, null,
                Duration.ofMillis(200), Duration.ofSeconds(1), f.toString(), null);
        P2pSettings s = new P2pSettings(p.maxNeighbors(), p.maxLatencyMs());
        P2pService svc = new P2pService(p, new PeerTable(s::effective), s, new SimpleMeterRegistry());
        svc.start();
        assertThat(s.effective().maxNeighbors()).isEqualTo(2);
        svc.stop();
    }

    @Test
    void theServiceStartsBeforeTheWebServerSoAdminCallsNeverMeetAHalfStartedNode() {
        P2pService svc = service(props(false, null, null, java.util.List.of()));
        assertThat(svc.getPhase()).isLessThan(Integer.MAX_VALUE - 1024);   // the web server starts at MAX_VALUE - 1
    }

    @Test
    void messagingIsUnavailableAfterStop() {
        P2pService svc = service(props(true, null, "n1", java.util.List.of()));
        svc.start();
        svc.stop();
        assertThatThrownBy(svc::messaging).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aStartThatFailsHalfWayReleasesTheClusterPort() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        SimpleMeterRegistry boom = new SimpleMeterRegistry() {
            @Override
            protected <T> Gauge newGauge(Meter.Id id, T obj, java.util.function.ToDoubleFunction<T> f) {
                throw new IllegalStateException("boom");
            }
        };
        P2pProperties p = new P2pProperties(true, "n1", port, "127.0.0.1", null, null, null,
                Duration.ofMillis(200), Duration.ofSeconds(1), null, null);
        P2pSettings s = new P2pSettings(null, null);
        P2pService failing = new P2pService(p, new PeerTable(s::effective), s, boom);
        assertThatThrownBy(failing::start).isInstanceOf(IllegalStateException.class);

        PeerCluster again = new PeerCluster("n2", port, "127.0.0.1", java.util.List.of());
        again.start().block(Duration.ofSeconds(10));   // fails if the first cluster still holds the port
        again.close();
    }

    @Test
    void aPersistFailureIsWarnedOnceNotOnEveryAttempt() throws Exception {
        Path blocker = Files.writeString(dir.resolve("blocker"), "a file where a directory is needed");
        P2pService svc = service(props(true, blocker.resolve("p2p.yaml").toString(), "n1", java.util.List.of()));
        svc.start();
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(P2pService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            svc.persistNow();
            svc.persistNow();
            svc.persistNow();
            assertThat(appender.list.stream().filter(e -> e.getLevel() == Level.WARN).count()).isEqualTo(1);
        } finally {
            logger.detachAppender(appender);
            svc.stop();
        }
    }

    @Test
    void requestPersistWritesTheFileWithoutBlockingTheCaller() {
        Path f = dir.resolve("p2p.yaml");
        P2pService svc = service(props(true, f.toString(), "n1", java.util.List.of()));
        svc.start();
        svc.requestPersist();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(f).exists());
        svc.stop();
    }
}
