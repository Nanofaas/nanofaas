package it.unimib.datai.nanofaas.modules.offload.oneshot.auction;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
class AuctionReferenceReplayTest {
    private record BidGroup(String target, String function, String version, long generation,
                            double price, boolean memoryOnly) {}

    private Map<BidGroup, Long> demand(List<OneShotAuctionEngine.BidProposal> proposals) {
        var result = new HashMap<BidGroup, Long>();
        for (var proposal : proposals) {
            var bid = proposal.bid();
            var group = new BidGroup(proposal.target(), bid.function(), bid.version(), bid.buyerGeneration(),
                    bid.price(), bid.memoryOnly());
            // Memory requests count individually; their zero quantity cannot encode multiplicity.
            result.merge(group, bid.memoryOnly() ? 1L : bid.quantity(), Long::sum);
        }
        return result;
    }

    @Test void aggregateAndUnitBidsDescribeTheSameDemand() {
        var aggregate = new OneShotAuctionEngine(OneShotAuctionEngine.Options.base());
        var unit = new OneShotAuctionEngine(new OneShotAuctionEngine.Options(0.01, 0, 0, 0, 0, true));
        var offers = List.of(
                new AuctionMessage.Offer("a", "a-run", "f", "v1", 4, 2, 128, 128, 1, 1, 0.1, 1, 0, 0),
                new AuctionMessage.Offer("b", "b-run", "f", "v1", 7, 1, 128, 128, 1, 1, 0.4, 0.8, 0, 0),
                new AuctionMessage.Offer("other-version", "run", "f", "v2", 8, 20, 128, 128, 1, 1, 0, 1, 0, 0),
                new AuctionMessage.Offer("other-function", "run", "g", "v1", 8, 20, 128, 128, 1, 1, 0, 1, 0, 0));
        for (long wanted : new long[]{0, 1, 3, 7}) {
            var bids = aggregate.defineBids("f", "v1", 3, wanted, 0.1, offers);
            assertThat(demand(bids)).isEqualTo(demand(unit.defineBids("f", "v1", 3, wanted, 0.1, offers)));
            assertThat(bids.stream().filter(p -> !p.bid().memoryOnly()).mapToLong(p -> p.bid().quantity()).sum())
                    .isEqualTo(Math.min(wanted, 3));
            assertThat(bids.stream().filter(p -> p.bid().memoryOnly()).count()).isEqualTo(wanted > 3 ? 2 : 0);
            assertThat(bids).allSatisfy(p -> {
                assertThat(p.target()).isIn("a", "b");
                assertThat(p.bid().function()).isEqualTo("f");
                assertThat(p.bid().version()).isEqualTo("v1");
                assertThat(p.bid().buyerGeneration()).isEqualTo(3);
            });
        }
    }

    @Test void aggregateBidCountDoesNotGrowWithQuantity() {
        var engine = new OneShotAuctionEngine(OneShotAuctionEngine.Options.base());
        for (long quantity : new long[]{1, 1000, 1000000, 9007199254740991L}) {
            var offer = new AuctionMessage.Offer("seller", "run", "f", "v1", 4, quantity, 0,
                    128, 1, 1, 0, 1, 0, 0);
            var proposals = engine.defineBids("f", "v1", 3, quantity, 0.1, List.of(offer));
            assertThat(proposals).singleElement().satisfies(p -> {
                assertThat(p.target()).isEqualTo("seller");
                assertThat(p.bid().quantity()).isEqualTo(quantity);
                assertThat(p.bid().price()).isEqualTo(0.01);
                assertThat(p.bid().memoryOnly()).isFalse();
            });
        }
    }

    @Test void aggregateBidOrderAmountsAndPricesMatchFrozenBaseTranscript() throws Exception {
        var root = new JsonMapper().readTree(getClass().getResourceAsStream("/one-shot/reference/auction-transcripts.json")).get(0);
        var engine = new OneShotAuctionEngine(OneShotAuctionEngine.Options.base());
        var offers = List.of(
                new AuctionMessage.Offer("node-1", "run-1", "f", "v1", 4, 2, 0, 128, 1, 1, 0, 1, 0, 0),
                new AuctionMessage.Offer("node-2", "run-2", "f", "v1", 7, 2, 0, 128, 1, 1, 0, 1, 0, 0));
        var bids = engine.defineBids("f", "v1", 3, 3, 0.1, offers);
        var expected = root.get("rounds").get(0).get("bids");
        assertThat(bids).hasSize(expected.size());
        for (int i = 0; i < bids.size(); i++) {
            assertThat(bids.get(i).target()).isEqualTo("node-" + expected.get(i).get("j").asInt());
            assertThat(bids.get(i).bid().quantity()).isEqualTo(expected.get(i).get("d").asLong());
            assertThat(bids.get(i).bid().price()).isEqualTo(expected.get(i).get("b").asDouble());
        }
    }
    @Test void proportionalMemoryRequestsMatchReferenceBeforeAnyAssignmentIsRoutable() throws Exception {
        var memory = new JsonMapper().readTree(getClass().getResourceAsStream("/one-shot/reference/auction-transcripts.json")).get(1);
        var functions = new java.util.ArrayList<it.unimib.datai.nanofaas.modules.offload.oneshot.solver.LocalProblem.Function>();
        for (int i = 0; i < 2; i++) functions.add(new it.unimib.datai.nanofaas.modules.offload.oneshot.solver.LocalProblem.Function(
                "f" + i, 0, 1, 1, memory.get("memoryMiB").get(i).asLong(), 1, 0.9, 0.1, 0, 0, 0, 0));
        var problem = new it.unimib.datai.nanofaas.modules.offload.oneshot.solver.LocalProblem(
                it.unimib.datai.nanofaas.modules.offload.oneshot.solver.LocalProblem.Model.LSP, 384, functions);
        var solution = new it.unimib.datai.nanofaas.modules.offload.oneshot.solver.LocalReplicaSolver().solve(problem,
                it.unimib.datai.nanofaas.modules.offload.oneshot.solver.SolveLimits.forDuration(java.time.Duration.ofSeconds(1)));
        var state = AuctionSnapshot.open("seller", "s-run", 1, 0, 1, SellerLedgerTest.START, SellerLedgerTest.START.plusSeconds(300), problem, solution,
                java.util.Map.of("f0", new AuctionSnapshot.FunctionIdentity("v0", 7), "f1", new AuctionSnapshot.FunctionIdentity("v1", 8)), java.util.Map.of("a", "a-run"));
        var ledger = new SellerLedger(state, OneShotAuctionEngine.Options.base());
        for (int f = 0; f < 2; f++) ledger.apply(AuctionMessage.bid(SellerLedgerTest.envelope("a", "a-run", 1, "memory" + f), "seller",
                new AuctionMessage.Bid("f" + f, "v" + f, 3, 0, 0, true)));
        var closed = ledger.apply(AuctionMessage.close(SellerLedgerTest.envelope("seller", "s-run", 1, "close"), "seller"));
        assertThat(closed.state().desiredReplicas().get("f0")).isEqualTo(memory.get("additionalReplicas").get(1).get(0).asInt());
        assertThat(closed.state().desiredReplicas().get("f1")).isEqualTo(memory.get("additionalReplicas").get(1).get(1).asInt());
        assertThat(closed.state().assignments()).isEmpty();
        assertThat(closed.emit()).isEmpty();
    }
}
