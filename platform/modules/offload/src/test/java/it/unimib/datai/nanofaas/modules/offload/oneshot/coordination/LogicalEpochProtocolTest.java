package it.unimib.datai.nanofaas.modules.offload.oneshot.coordination;

import it.unimib.datai.nanofaas.p2papi.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.auction.OneShotAuctionEngine;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LogicalEpochProtocolTest {
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void offsetsAndUtcJumpsDoNotChangeTwoNodeAuctionDecisions() {
        var normal = run(false);
        var shifted = run(true);
        for (int i = 0; i < 2; i++) {
            assertThat(shifted.get(i).status()).isEqualTo(EpochOutcome.Status.FIXED_ROUNDS_COMPLETED);
            assertThat(shifted.get(i).snapshot().desiredReplicas()).isEqualTo(normal.get(i).snapshot().desiredReplicas());
            assertThat(shifted.get(i).snapshot().prices()).isEqualTo(normal.get(i).snapshot().prices());
            assertThat(shifted.get(i).snapshot().assignments().values().stream().mapToLong(a -> a.quantity()).sum())
                    .isEqualTo(normal.get(i).snapshot().assignments().values().stream().mapToLong(a -> a.quantity()).sum());
        }
    }

    @Test
    void inputFreezeConsumesTheSameMonotonicAuctionBudgetAsTheSolver() {
        var network = new ConcurrentHashMap<String, Link>();
        var link = new Link("a", network);
        var nano = new AtomicLong(100);
        var freezes = new AtomicInteger();
        try (var node = node(link, (epoch, from, until) -> {
            freezes.incrementAndGet();
            nano.addAndGet(Duration.ofSeconds(3).toNanos());
            return EpochProtocolTest.input("a", epoch, from, until);
        }, () -> FROM.minusSeconds(30), nano::get)) {
            node.start();
            var outcome = node.prepareLogical("session", 0, FROM, FROM.plusSeconds(60), Duration.ofSeconds(5)).block(Duration.ofSeconds(5));
            assertThat(outcome.status()).isEqualTo(EpochOutcome.Status.DEADLINE);
            assertThat(outcome.snapshot()).isNull();
            assertThat(freezes.get()).isEqualTo(1);
        }
    }

    @Test
    void duplicatePreparationCannotStartAnotherAuctionOrRefreezeInputs() {
        var network = new ConcurrentHashMap<String, Link>();
        var link = new Link("a", network);
        var freezes = new AtomicInteger();
        try (var node = node(link, (epoch, from, until) -> {
            freezes.incrementAndGet();
            return EpochProtocolTest.input("a", epoch, from, until);
        }, () -> FROM.plusSeconds(30), System::nanoTime)) {
            node.start();
            assertThat(node.prepareLogical("session", 0, FROM, FROM.plusSeconds(60), Duration.ofSeconds(5)).block(Duration.ofSeconds(5)).status())
                    .isEqualTo(EpochOutcome.Status.FIXED_ROUNDS_COMPLETED);
            assertThat(node.prepareLogical("session", 0, FROM, FROM.plusSeconds(60), Duration.ofSeconds(5)).block(Duration.ofSeconds(5)).status())
                    .isEqualTo(EpochOutcome.Status.FAILED);
            assertThat(freezes.get()).isEqualTo(1);
        }
    }

    @Test
    void logicalWireCannotBeConfusedWithLegacyOrAnotherSession() {
        var codec = new AuctionCodec();
        var batch = new AuctionCodec.Batch(1, "a", "a-run", 0, 0, AuctionCodec.Phase.HELLO, FROM, FROM.plusSeconds(60), false, List.of());
        var logical = codec.encodeLogical("session", 10, batch);
        assertThat(codec.decodeLogical(logical).sessionId()).isEqualTo("session");
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() -> codec.decodeLogical(codec.encode(batch)));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(logical));
    }

    @Test
    void stoppingDuringInputFreezeCannotInstallWorkInTheRestartedLifecycle() throws Exception {
        var network = new ConcurrentHashMap<String, Link>();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var node = node(new Link("a", network), (epoch, from, until) -> {
            entered.countDown();
            try { if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("freeze timed out"); }
            catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
            return EpochProtocolTest.input("a", epoch, from, until);
        }, () -> FROM, System::nanoTime)) {
            node.start();
            var result = node.prepareLogical("session", 0, FROM, FROM.plusSeconds(60), Duration.ofSeconds(5))
                    .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).toFuture();
            try {
                assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                node.stop(); node.start(); release.countDown();
                assertThat(result.get(3, java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo(EpochOutcome.Status.FAILED);
                assertThat(node.lastPreparedEpoch()).isEqualTo(-1);
            } finally { release.countDown(); }
        }
    }

    @Test
    void solverAndAuctionDeadlinesSurviveSignedMonotonicWrap() {
        var network = new ConcurrentHashMap<String, Link>();
        var nano = new AtomicLong(Long.MAX_VALUE - 10);
        try (var node = node(new Link("a", network), (epoch, from, until) -> {
            nano.addAndGet(20);
            return EpochProtocolTest.input("a", epoch, from, until);
        }, () -> { throw new AssertionError("logical epoch read UTC"); }, nano::get)) {
            node.start();
            var outcome = node.prepareLogical("session", 0, FROM, FROM.plusSeconds(60), Duration.ofSeconds(5)).block();
            assertThat(outcome.status()).isEqualTo(EpochOutcome.Status.FIXED_ROUNDS_COMPLETED);
            assertThat(outcome.rounds()).isEqualTo(10);
            assertThat(outcome.auctionNanos()).isEqualTo(20);
            assertThat(node.prepareLogical("another-session", 1, FROM.plusSeconds(60), FROM.plusSeconds(120), Duration.ofSeconds(5)).block().status())
                    .isEqualTo(EpochOutcome.Status.FAILED);
        }
    }

    private static List<EpochOutcome> run(boolean shifted) {
        var network = new ConcurrentHashMap<String, Link>();
        var a = new Link("a", network);
        var b = new Link("b", network);
        var wallReads = new AtomicInteger();
        var walls = List.of(new AtomicReference<>(FROM.plusSeconds(shifted ? 30 : 0)),
                new AtomicReference<>(FROM.minusSeconds(shifted ? 30 : 0)));
        List<Link> links = List.of(a, b);
        var nodes = java.util.stream.IntStream.range(0, 2).mapToObj(i -> {
            links.get(i).onDelivery = () -> walls.get(i).updateAndGet(t -> t.plusSeconds(shifted ? (i == 0 ? 3600 : -3600) : 0));
            return node(links.get(i), (epoch, from, until) -> EpochProtocolTest.input(links.get(i).id, epoch, from, until),
                    () -> { wallReads.incrementAndGet(); return walls.get(i).get(); }, System::nanoTime);
        }).toList();
        try {
            nodes.forEach(EpochCoordinator::start);
            var outcomes = Flux.merge(nodes.stream().map(node -> node.prepareLogical("session", 0, FROM, FROM.plusSeconds(60), Duration.ofSeconds(5))).toList())
                    .collectList().block(Duration.ofSeconds(10));
            assertThat(wallReads.get()).isZero();
            assertThat(outcomes).allMatch(outcome -> outcome.status() == EpochOutcome.Status.FIXED_ROUNDS_COMPLETED);
            return outcomes.stream().sorted(java.util.Comparator.comparing(o -> o.snapshot().nodeId())).toList();
        } finally {
            nodes.forEach(EpochCoordinator::close);
        }
    }

    @Test
    void completedResultAllowsImmediatePreparationOfTheNextEpoch() {
        var network=new ConcurrentHashMap<String,Link>();
        try(var node=node(new Link("a",network),(epoch,from,until)->EpochProtocolTest.input("a",epoch,from,until),()->FROM,System::nanoTime)) {
            node.start();
            var result=node.prepareLogical("session",0,FROM,FROM.plusSeconds(60),Duration.ofSeconds(5))
                    .flatMap(first->node.prepareLogical("session",1,FROM.plusSeconds(60),FROM.plusSeconds(120),Duration.ofSeconds(5)))
                    .block(Duration.ofSeconds(5));
            assertThat(result.status()).as(result.reason()).isEqualTo(EpochOutcome.Status.FIXED_ROUNDS_COMPLETED);
        }
    }

    @Test
    void restartDuringPeerSnapshotCannotInstallRetiredPreparation() {
        var network=new ConcurrentHashMap<String,Link>();
        var link=org.mockito.Mockito.spy(new Link("a",network));
        var restarted=new java.util.concurrent.atomic.AtomicBoolean();
        try(var node=node(link,(epoch,from,until)->EpochProtocolTest.input("a",epoch,from,until),()->FROM,System::nanoTime)) {
            node.start();
            org.mockito.Mockito.doAnswer(call->{
                if(restarted.compareAndSet(false,true)) { node.stop();node.start(); }
                return call.callRealMethod();
            }).when(link).activeNeighbors();
            var result=node.prepareLogical("session",0,FROM,FROM.plusSeconds(60),Duration.ofSeconds(5)).block(Duration.ofSeconds(5));
            assertThat(result.status()).isEqualTo(EpochOutcome.Status.FAILED);
            assertThat(node.lastPreparedEpoch()).isEqualTo(-1);
        }
    }

    private static EpochCoordinator node(Link link, EpochInput.Factory input, java.util.function.Supplier<Instant> wall,
                                         java.util.function.LongSupplier nano) {
        var health = new ClockHealth(Duration.ofMillis(100), Duration.ofSeconds(10), wall);
        return new EpochCoordinator(link, input, EpochProtocolTest.settings(10), health, wall, OneShotAuctionEngine.Options.base(), true, nano);
    }

    private static final class Link implements PeerTransport {
        final String id;
        final Map<String, Link> network;
        final Map<String, PeerReceiver> receivers = new ConcurrentHashMap<>();
        Runnable onDelivery = () -> { };
        Link(String id, Map<String, Link> network) { this.id = id; this.network = network; network.put(id, this); }
        PeerEndpoint endpoint() { return new PeerEndpoint(id, id + "-run", URI.create("http://" + id + ":8080")); }
        @Override public Optional<PeerEndpoint> localEndpoint() { return Optional.of(endpoint()); }
        @Override public List<PeerEndpoint> activeNeighbors() { return network.values().stream().filter(link -> link != this).map(Link::endpoint).toList(); }
        @Override public PeerSubscription subscribe(String topic, PeerReceiver receiver) { receivers.put(topic, receiver); return () -> receivers.remove(topic, receiver); }
        @Override public Mono<byte[]> request(String peer, String topic, byte[] payload, Duration timeout) {
            return Mono.defer(() -> {
                var target = network.get(peer);
                var receiver = target.receivers.get(topic);
                if (receiver == null) return Mono.just(new byte[]{0});
                target.onDelivery.run();
                return receiver.onMessage(id, payload).flatMap(reply -> receiver.onMessage(id, payload));
            });
        }
    }
}
