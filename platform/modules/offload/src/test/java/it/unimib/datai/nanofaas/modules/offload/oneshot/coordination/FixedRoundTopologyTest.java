package it.unimib.datai.nanofaas.modules.offload.oneshot.coordination;

import it.unimib.datai.nanofaas.p2papi.*;
import it.unimib.datai.nanofaas.forecastingapi.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.solver.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class FixedRoundTopologyTest {
    private static final class Wire implements PeerTransport {
        final String id;
        final Map<String, Wire> network;
        final List<String> neighbors;
        volatile PeerReceiver receiver;

        Wire(String id, Map<String, Wire> network, List<String> neighbors) {
            this.id = id;
            this.network = network;
            this.neighbors = neighbors;
            network.put(id, this);
        }
        PeerEndpoint endpoint() { return new PeerEndpoint(id, id + "-run", URI.create("http://" + id + ":8080")); }
        public Optional<PeerEndpoint> localEndpoint() { return Optional.of(endpoint()); }
        public List<PeerEndpoint> activeNeighbors() { return neighbors.stream().map(n -> network.get(n).endpoint()).toList(); }
        public PeerSubscription subscribe(String topic, PeerReceiver receiver) {
            this.receiver = receiver;
            return () -> this.receiver = null;
        }
        public Mono<byte[]> request(String peer, String topic, byte[] payload, Duration timeout) {
            return Mono.defer(() -> network.get(peer).receiver.onMessage(id, payload));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"two-triangles", "line", "complete"})
    void quietNodesCompleteEveryRoundAndFinalizeAssignments(String topology) {
        var network = new ConcurrentHashMap<String, Wire>();
        var ids = List.of("a", "b", "c", "d", "e");
        var triangles = Map.of("a", List.of("b", "c"), "b", List.of("a", "c"),
                "c", List.of("a", "b", "d", "e"), "d", List.of("c", "e"), "e", List.of("c", "d"));
        var wires = ids.stream().map(id -> new Wire(id, network, switch (topology) {
            case "two-triangles" -> triangles.get(id);
            case "line" -> ids.stream().filter(other -> Math.abs(ids.indexOf(other) - ids.indexOf(id)) == 1).toList();
            default -> ids.stream().filter(other -> !other.equals(id)).toList();
        })).toList();
        var nodes = wires.stream().map(w -> new EpochCoordinator(w, (epoch, from, until) -> {
            var base = EpochProtocolTest.input(w.id, epoch, from, until);
            double load = w.id.equals("a") ? 7 : w.id.equals("c") ? 2 : 0;
            var problem = new LocalProblem(LocalProblem.Model.LSP, 2, List.of(
                    new LocalProblem.Function("f", load, 1, 1, 1, 1, .9, .1, 0, 0, 0, 0)));
            var forecasts = Map.of("f", new ForecastSnapshot(new ForecastQuery(w.id, "f",
                    base.identities().get("f").generation(), from, until), ForecastSnapshot.Status.AVAILABLE,
                    load, 1, "oracle", Instant.now()));
            return new EpochInput(problem, base.identities(), forecasts, 1);
        }, EpochProtocolTest.settings(10), EpochProtocolTest.health())).toList();
        try {
            nodes.forEach(EpochCoordinator::start);
            var from = Instant.now().plusSeconds(10);
            var outcomes = Mono.zip(nodes.stream().map(n -> n.prepare(1, from, from.plusSeconds(300))).toList(),
                    array -> Arrays.stream(array).map(o -> (EpochOutcome) o).toList()).block(Duration.ofSeconds(8));
            assertThat(outcomes).hasSize(5).allSatisfy(outcome -> {
                assertThat(outcome.rounds()).as("%s: %s", topology, outcome.reason()).isEqualTo(10);
                assertThat(outcome.status().name()).isEqualTo("FIXED_ROUNDS_COMPLETED");
                assertThat(outcome.snapshot().status()).isEqualTo("FINALIZED");
                assertThat(outcome.censored()).isFalse();
            });
            assertThat(outcomes.getFirst().snapshot().assignments()).isNotEmpty();
        } finally {
            nodes.forEach(EpochCoordinator::close);
        }
    }
}
