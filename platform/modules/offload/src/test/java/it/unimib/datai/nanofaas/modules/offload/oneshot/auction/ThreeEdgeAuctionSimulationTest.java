package it.unimib.datai.nanofaas.modules.offload.oneshot.auction;
import it.unimib.datai.nanofaas.modules.offload.oneshot.solver.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ThreeEdgeAuctionSimulationTest {
    AuctionMessage.Envelope envelope(String node, int round, String id) {
        return new AuctionMessage.Envelope(1, node, node + "-run", 1, round, id, 1, SellerLedgerTest.START, SellerLedgerTest.START.plusSeconds(300));
    }
    void verify(Map<String,SellerLedger> nodes) {
        for (var ledger : nodes.values()) {
            var state = ledger.snapshot();
            int replicas = state.desiredReplicas().get("f");
            assertThat(replicas).isBetween(0, 2);
            double inbound = state.assignments().values().stream().filter(a -> a.sellerId().equals(state.nodeId())).mapToLong(Assignment::quantity).sum();
            assertThat(inbound + state.baseProblem().functions().getFirst().fixedLocal()).isLessThanOrEqualTo(replicas);
            assertThat(state.assignments().values()).allMatch(a -> a.epoch() == 1 && a.quantity() >= 0);
        }
    }
    @Test void threeEdgesAndTerminalCloudConserveFlowAcrossMemoryBidsGrantsAndReadiness() {
        var nodes = new LinkedHashMap<String,SellerLedger>();
        var engine = new OneShotAuctionEngine(OneShotAuctionEngine.Options.base());
        for (String node : List.of("a", "b", "c")) {
            long generation = node.equals("a") ? 3 : node.equals("b") ? 7 : 9;
            var problem = new LocalProblem(LocalProblem.Model.LSP, 2, List.of(new LocalProblem.Function("f", node.equals("a") ? 7 : 0, 1, 1, 1, 1, 0.9, 0.1, 0, 0, 0, 0)));
            var initial = new LocalReplicaSolver().solve(problem, SolveLimits.forDuration(Duration.ofSeconds(1)));
            var peers = new LinkedHashMap<String,String>();
            for (String other : List.of("a", "b", "c")) if (!other.equals(node)) peers.put(other, other + "-run");
            nodes.put(node, new SellerLedger(AuctionSnapshot.open(node, node + "-run", 1, 0, 1,
                    SellerLedgerTest.START, SellerLedgerTest.START.plusSeconds(300), problem, initial,
                    Map.of("f", new AuctionSnapshot.FunctionIdentity("image-v1", generation)), peers), OneShotAuctionEngine.Options.base()));
        }
        for (int round = 0; round < 2; round++) {
            var offers = new ArrayList<AuctionMessage.Offer>();
            offers.addAll(engine.localOffers(nodes.get("b").snapshot())); offers.addAll(engine.localOffers(nodes.get("c").snapshot()));
            var bids = engine.defineBids("f", "image-v1", 3, 5, 0.1, offers);
            int seq = 0;
            for (var bid : bids) {
                nodes.get(bid.target()).apply(AuctionMessage.bid(envelope("a", round, "bid" + seq++), bid.target(), bid.bid())); verify(nodes);
            }
            for (String seller : List.of("b", "c")) {
                var closed = nodes.get(seller).apply(AuctionMessage.close(envelope(seller, round, "close"), seller)); verify(nodes);
                for (var grant : closed.emit()) { nodes.get("a").apply(grant); verify(nodes); }
            }
            nodes.get("a").apply(AuctionMessage.close(envelope("a", round, "close"), "a")); verify(nodes);
            if (round == 0) nodes.values().forEach(SellerLedger::nextRound);
        }
        assertThat(nodes.get("a").snapshot().assignments().values().stream().mapToLong(Assignment::quantity).sum()).isEqualTo(4);
        for (String seller : List.of("b", "c")) {
            var assignment = nodes.get(seller).snapshot().assignments().values().iterator().next();
            var confirmed = assignment.confirm(seller.equals("b") ? 1 : 2);
            var transition = nodes.get(seller).apply(new AuctionMessage(envelope(seller, 1, "ready"), AuctionMessage.Kind.READY_CONFIRM,
                    seller, null, null, confirmed));
            assertThat(transition.emit()).hasSize(1);
            nodes.get("a").apply(transition.emit().getFirst()); verify(nodes);
        }
        double local = 2;
        long edge = nodes.get("a").snapshot().assignments().values().stream().filter(Assignment::readyConfirmed).mapToLong(Assignment::quantity).sum();
        double terminalCloud = 7 - local - edge;
        assertThat(edge).isEqualTo(3); assertThat(terminalCloud).isEqualTo(2);
        assertThat(local + edge + terminalCloud).isEqualTo(7);
    }
}
