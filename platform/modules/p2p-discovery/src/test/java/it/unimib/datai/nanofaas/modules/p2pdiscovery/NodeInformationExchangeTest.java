package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class NodeInformationExchangeTest {
    @Test void changingOneFlagPreservesOtherCategoriesAndTheirCollectors() {
        var settings = new P2pSettings(null, null, new P2pSettings.Sharing(true, false, true));
        var table = new PeerTable(settings::effective);
        var collector = org.mockito.Mockito.mock(NodeInformationCollector.class);
        org.mockito.Mockito.when(collector.collectFunctions()).thenReturn(snapshot("local").functions());
        org.mockito.Mockito.when(collector.collectResources()).thenReturn(NodeInformation.Category.unavailable("TEST"));
        org.mockito.Mockito.when(collector.collectImages(org.mockito.ArgumentMatchers.any()))
                .thenReturn(NodeInformation.Category.unavailable("NO_PROVIDER"));
        var wire = new PeerMessaging.Wire() {
            public Mono<Void> send(String a, String t, byte[] p) { return Mono.empty(); }
            public Mono<byte[]> request(String a, String t, byte[] p, Duration d) { return Mono.empty(); }
            public void handle(String t, PeerCluster.Handler h) {}
        };
        try (var exchange = new NodeInformationExchange("local", settings, table, collector, System::nanoTime)) {
            exchange.activate(new PeerMessaging(wire, table), false);
            exchange.collect();
            await().untilAsserted(() -> assertThat(exchange.local().functions().status()).isEqualTo(NodeInformation.Status.AVAILABLE));
            settings.patch(Map.of("shareImages", true));
            exchange.settingsChanged();
            await().untilAsserted(() -> assertThat(exchange.local().images().reasonCode()).isEqualTo("NO_PROVIDER"));
            org.mockito.Mockito.verify(collector, org.mockito.Mockito.times(1)).collectFunctions();
            org.mockito.Mockito.verify(collector, org.mockito.Mockito.times(1)).collectResources();
            settings.patch(Map.of("maxNeighbors", 2));
            exchange.settingsChanged();
            assertThat(exchange.local().functions().status()).isEqualTo(NodeInformation.Status.AVAILABLE);
        }
    }

    @Test void localInspectionAppliesTheSameCategoryLimitsAsTheWire() {
        var settings = new P2pSettings(null, null, new P2pSettings.Sharing(true, false, false));
        var collector = org.mockito.Mockito.mock(NodeInformationCollector.class);
        var functions = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> new NodeInformation.FunctionInfo("fn" + i, "LOCAL", "x".repeat(4000), null)).toList();
        org.mockito.Mockito.when(collector.collectFunctions()).thenReturn(new NodeInformation.Category<>(
                NodeInformation.Status.AVAILABLE, Instant.now(), "catalog", "node", null, functions, 0));
        var wire = new PeerMessaging.Wire() {
            public Mono<Void> send(String a, String t, byte[] p) { return Mono.empty(); }
            public Mono<byte[]> request(String a, String t, byte[] p, Duration d) { return Mono.empty(); }
            public void handle(String t, PeerCluster.Handler h) {}
        };
        var table = new PeerTable(settings::effective);
        try (var exchange = new NodeInformationExchange("local", settings, table, collector, System::nanoTime)) {
            exchange.activate(new PeerMessaging(wire, table), false);
            exchange.collect();
            await().untilAsserted(() -> assertThat(exchange.local().functions().reasonCode()).isEqualTo("LIMIT_EXCEEDED"));
            assertThat(exchange.local().functions().data()).isNull();
        }
    }
    @Test void limitsPeerConcurrencyAndDoesNotOverlapPollingRounds() {
        var settings = new P2pSettings(null, null);
        var table = new PeerTable(settings::effective);
        for (int i = 0; i < 10; i++) table.upsert("peer-" + i, "address-" + i);
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var pending = Sinks.many().multicast().<byte[]>onBackpressureBuffer();
        var wire = new PeerMessaging.Wire() {
            public Mono<Void> send(String a, String t, byte[] p) { return Mono.empty(); }
            public Mono<byte[]> request(String a, String t, byte[] p, Duration d) {
                requests.incrementAndGet();
                return pending.asFlux().next();
            }
            public void handle(String t, PeerCluster.Handler h) {}
        };
        try (var exchange = new NodeInformationExchange("local", settings, table, collector(), System::nanoTime)) {
            exchange.activate(new PeerMessaging(wire, table), false);
            exchange.poll();
            exchange.poll();
            assertThat(requests).hasValue(4);
            exchange.deactivate();
        }
    }

    @Test void advertisedAgeExpiresEvenWhenReceiptIsFresh() {
        var settings = new P2pSettings(null, null);
        var table = new PeerTable(settings::effective);
        table.upsert("remote", "remote");
        var clock = new AtomicLong(0);
        var base = snapshot("remote");
        var info = new NodeInformation(1, "remote", base.sampledAt(), base.functions().aged(14_000),
                base.images(), base.resources());
        var wire = new PeerMessaging.Wire() {
            public Mono<Void> send(String a, String t, byte[] p) { return Mono.empty(); }
            public Mono<byte[]> request(String a, String t, byte[] p, Duration d) {
                return Mono.just(new NodeInformationCodec().encode(info));
            }
            public void handle(String t, PeerCluster.Handler h) {}
        };
        try (var exchange = new NodeInformationExchange("local", settings, table, collector(), clock::get)) {
            exchange.activate(new PeerMessaging(wire, table), false);
            exchange.poll();
            clock.set(Duration.ofSeconds(2).toNanos());
            var functions = exchange.remote("remote").orElseThrow().information().functions();
            assertThat(functions.reasonCode()).isEqualTo("STALE");
            assertThat(functions.data()).isNull();
        }
    }
    @Test void disabledImagesNeverCallBackendAndTimedOutCallsCannotOverlapOrPublishLate() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var release = new java.util.concurrent.CountDownLatch(1);
        var settings = new P2pSettings(4, 1000.0);
        var table = new PeerTable(settings::effective);
        var collector = new NodeInformationCollector(null, null, (timeout, max) -> {
            calls.incrementAndGet();
            boolean done = false;
            while (!done) {
                try { release.await(); done = true; }
                catch (InterruptedException ignored) { /* simulates an uninterruptible backend */ }
            }
            return new it.unimib.datai.nanofaas.controlplane.deployment.ImageInventory("test", "test",
                    Instant.now(), it.unimib.datai.nanofaas.controlplane.deployment.ImageInventory.Status.AVAILABLE,
                    null, java.util.List.of());
        }, new SimpleMeterRegistry(), Clock.systemUTC(),
                () -> new NodeInformationCollector.EnvironmentMemory(-1, -1));
        var wire = new PeerMessaging.Wire() {
            public Mono<Void> send(String a, String t, byte[] p) { return Mono.empty(); }
            public Mono<byte[]> request(String a, String t, byte[] p, Duration d) { return Mono.empty(); }
            public void handle(String t, PeerCluster.Handler h) {}
        };
        try (var exchange = new NodeInformationExchange("local", settings, table, collector, System::nanoTime)) {
            exchange.activate(new PeerMessaging(wire, table), false);
            exchange.collect();
            assertThat(calls).hasValue(0);
            settings.patch(Map.of("shareImages", true));
            exchange.settingsChanged();
            await().atMost(Duration.ofSeconds(4)).untilAsserted(() ->
                    assertThat(exchange.local().images().reasonCode()).isEqualTo("TIMEOUT"));
            exchange.collect();
            assertThat(calls).hasValue(1);
            settings.patch(Map.of("shareImages", false));
            exchange.settingsChanged();
            release.countDown();
            assertThat(exchange.local().images().status()).isEqualTo(NodeInformation.Status.DISABLED);
            assertThat(exchange.local().images().data()).isNull();
        } finally { release.countDown(); }
    }

    @Test void receivesWithSharingOffAndRejectsLateResponseAfterPeerReactivation() {
        var settings = new P2pSettings(4, 1000.0);
        var table = new PeerTable(settings::effective);
        table.upsert("remote", "127.0.0.1:1234");
        table.setApiMode("remote", PeerMode.FORCE_ACTIVE);
        var pending = Sinks.<byte[]>one();
        var wire = new PeerMessaging.Wire() {
            public Mono<Void> send(String a, String t, byte[] p) { return Mono.empty(); }
            public Mono<byte[]> request(String a, String t, byte[] p, Duration d) { return pending.asMono(); }
            public void handle(String topic, PeerCluster.Handler handler) {}
        };
        var clock = new AtomicLong(100);
        try (var exchange = new NodeInformationExchange("local", settings, table, collector(), clock::get)) {
            exchange.activate(new PeerMessaging(wire, table), false);
            exchange.poll();
            table.setApiMode("remote", PeerMode.EXCLUDED);
            table.setApiMode("remote", PeerMode.FORCE_ACTIVE);
            pending.tryEmitValue(new NodeInformationCodec().encode(snapshot("remote")));
            assertThat(exchange.remote("remote").orElseThrow().state()).isEqualTo("NOT_RECEIVED");
            assertThat(exchange.local().functions().status()).isEqualTo(NodeInformation.Status.DISABLED);
        }
    }

    @Test void receiptCannotExtendSourceAgeAndIsolationClearsCache() {
        var settings = new P2pSettings(4, 1000.0);
        var table = new PeerTable(settings::effective);
        table.upsert("remote", "remote");
        table.setApiMode("remote", PeerMode.FORCE_ACTIVE);
        var nanos = new AtomicLong(0);
        var remote = snapshot("remote");
        var wire = new PeerMessaging.Wire() {
            public Mono<Void> send(String a, String t, byte[] p) { return Mono.empty(); }
            public Mono<byte[]> request(String a, String t, byte[] p, Duration d) {
                return Mono.just(new NodeInformationCodec().encode(remote));
            }
            public void handle(String t, PeerCluster.Handler h) {}
        };
        try (var exchange = new NodeInformationExchange("local", settings, table, collector(), nanos::get)) {
            exchange.activate(new PeerMessaging(wire, table), false);
            exchange.poll();
            assertThat(exchange.remote("remote").orElseThrow().state()).isEqualTo("CURRENT");
            nanos.set(Duration.ofSeconds(16).toNanos());
            assertThat(exchange.remote("remote").orElseThrow().state()).isEqualTo("STALE");
            assertThat(exchange.remote("remote").orElseThrow().information()).isNull();
            exchange.deactivate();
            assertThat(exchange.remote("remote").orElseThrow().state()).isEqualTo("INACTIVE");
        }
    }

    @Test void everySharingCombinationMasksImmediately() {
        var settings = new P2pSettings(4, 1000.0);
        var table = new PeerTable(settings::effective);
        var wire = new PeerMessaging.Wire() {
            public Mono<Void> send(String a, String t, byte[] p) { return Mono.empty(); }
            public Mono<byte[]> request(String a, String t, byte[] p, Duration d) { return Mono.empty(); }
            public void handle(String t, PeerCluster.Handler h) {}
        };
        try (var exchange = new NodeInformationExchange("local", settings, table, collector(), System::nanoTime)) {
            exchange.activate(new PeerMessaging(wire, table), false);
            for (int mask = 0; mask < 8; mask++) {
                settings.patch(Map.of("shareFunctions", (mask & 1) != 0, "shareImages", (mask & 2) != 0,
                        "shareResources", (mask & 4) != 0));
                exchange.settingsChanged();
                var info = exchange.local();
                assertThat(info.functions().status() == NodeInformation.Status.DISABLED).isEqualTo((mask & 1) == 0);
                assertThat(info.images().status() == NodeInformation.Status.DISABLED).isEqualTo((mask & 2) == 0);
                assertThat(info.resources().status() == NodeInformation.Status.DISABLED).isEqualTo((mask & 4) == 0);
            }
        }
    }

    static NodeInformation snapshot(String id) {
        return new NodeInformation(1, id, Instant.now(),
                new NodeInformation.Category<>(NodeInformation.Status.AVAILABLE, Instant.now(), "catalog",
                        "local-node", null, java.util.List.of(), 0),
                NodeInformation.Category.disabled(), NodeInformation.Category.disabled());
    }
    static NodeInformationCollector collector() {
        return new NodeInformationCollector(null, null, null, new SimpleMeterRegistry(),
                Clock.systemUTC(), () -> new NodeInformationCollector.EnvironmentMemory(-1, -1));
    }
}
