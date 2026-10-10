package it.unimib.datai.nanofaas.modules.offload.oneshot.coordination;
import it.unimib.datai.nanofaas.modules.p2pdiscovery.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
class OneShotPeerIntegrationTest {
    record Node(P2pService service,DefaultPeerTransport transport,EpochCoordinator coordinator) {}
    Node node(String id,List<String> seeds) {
        var properties=new P2pProperties(true,id,0,"127.0.0.1",seeds,null,null,Duration.ofMillis(200),Duration.ofSeconds(1),null,null);
        var settings=new P2pSettings(properties.maxNeighbors(),properties.maxLatencyMs());
        var table=new PeerTable(settings::effective);
        var service=new P2pService(properties,table,settings,new SimpleMeterRegistry()); service.start();
        var transport=new DefaultPeerTransport(table,service::transportSession,URI.create("http://"+id+":8080"),4); transport.start();
        var coordinator=new EpochCoordinator(transport,(epoch,from,until)->EpochProtocolTest.input(id,epoch,from,until),EpochProtocolTest.settings(10),EpochProtocolTest.health()); coordinator.start();
        return new Node(service,transport,coordinator);
    }
    @Test void threeRealP2pServicesExchangeVersionedAuctionBatches() {
        var nodes=new ArrayList<Node>();
        try {
            var a=node("a",List.of());nodes.add(a);
            nodes.add(node("b",List.of(a.service().address())));nodes.add(node("c",List.of(a.service().address())));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(()->nodes.forEach(n->assertThat(n.transport().activeNeighbors()).hasSize(2)));
            var from=Instant.now().plusSeconds(10);
            var results=Mono.zip(nodes.stream().map(n->n.coordinator().prepare(1,from,from.plusSeconds(300))).toList(),objects->Arrays.stream(objects).map(o->(EpochOutcome)o).toList()).block(Duration.ofSeconds(5));
            assertThat(results).allSatisfy(r->assertThat(r.status()).withFailMessage(r.reason()).isEqualTo(EpochOutcome.Status.CONVERGED));
            assertThat(results.getFirst().snapshot().assignments()).hasSize(2);
        } finally {
            nodes.forEach(n->n.coordinator().close()); nodes.forEach(n->n.transport().stop()); nodes.reversed().forEach(n->n.service().stop());
        }
    }
    private Node heterogeneousNode(String id, List<String> seeds, double demand) {
        var properties = new P2pProperties(true, id, 0, "127.0.0.1", seeds, null, null,
                Duration.ofMillis(200), Duration.ofSeconds(1), null, null);
        var settings = new P2pSettings(properties.maxNeighbors(), properties.maxLatencyMs());
        var table = new PeerTable(settings::effective);
        var service = new P2pService(properties, table, settings, new SimpleMeterRegistry());
        service.start();
        var transport = new DefaultPeerTransport(table, service::transportSession, URI.create("http://" + id + ":8080"), 4);
        transport.start();
        var coordinator = new EpochCoordinator(transport, (epoch, from, until) -> {
            var base = EpochProtocolTest.input(id, epoch, from, until);
            var functions = base.problem().functions().stream().map(f ->
                    new it.unimib.datai.nanofaas.modules.offload.oneshot.solver.LocalProblem.Function(
                            f.id(), f.load(), demand, f.utilization(), f.memoryMiB(),
                            f.alpha(), f.delta(), f.gamma(), 0, 0, 0, 0)).toList();
            return new EpochInput(new it.unimib.datai.nanofaas.modules.offload.oneshot.solver.LocalProblem(
                    base.problem().model(), base.problem().memoryCapacityMiB(), functions),
                    base.identities(), base.forecasts(), 1, 1, null, 2000000, 64L * 1024 * 1024,
                    "sha256:" + (id.equals("a") ? "d" : "e").repeat(64));
        }, EpochProtocolTest.settings(10), EpochProtocolTest.health());
        coordinator.start();
        return new Node(service, transport, coordinator);
    }

    @Test void compatibleImagesNegotiateWithDistinctLocalDemandAndProfileHashes() {
        var nodes = new ArrayList<Node>();
        try {
            var slow = heterogeneousNode("a", List.of(), 1.5);
            nodes.add(slow);
            nodes.add(heterogeneousNode("b", List.of(slow.service().address()), 0.5));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> nodes.forEach(
                    n -> assertThat(n.transport().activeNeighbors()).hasSize(1)));
            var from = Instant.now().plusSeconds(10);
            var results = Mono.zip(nodes.stream().map(n -> n.coordinator().prepare(1, from, from.plusSeconds(300))).toList(),
                    objects -> Arrays.stream(objects).map(o -> (EpochOutcome) o).toList()).block(Duration.ofSeconds(5));
            assertThat(results).allSatisfy(r -> assertThat(r.status()).withFailMessage(r.reason()).isEqualTo(EpochOutcome.Status.CONVERGED));
            assertThat(results.getFirst().snapshot().assignments()).isNotEmpty();
        } finally {
            nodes.forEach(n -> n.coordinator().close());
            nodes.forEach(n -> n.transport().stop());
            nodes.reversed().forEach(n -> n.service().stop());
        }
    }

}
