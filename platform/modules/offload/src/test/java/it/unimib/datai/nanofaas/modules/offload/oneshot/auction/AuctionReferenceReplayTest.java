package it.unimib.datai.nanofaas.modules.offload.oneshot.auction;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
class AuctionReferenceReplayTest {
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
