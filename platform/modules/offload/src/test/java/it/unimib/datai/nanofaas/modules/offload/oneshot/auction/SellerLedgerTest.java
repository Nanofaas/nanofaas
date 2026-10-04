package it.unimib.datai.nanofaas.modules.offload.oneshot.auction;
import it.unimib.datai.nanofaas.modules.offload.oneshot.solver.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class SellerLedgerTest {
    static final Instant START = Instant.parse("2026-10-04T12:00:00Z");
    AuctionSnapshot state() {
        var problem = new LocalProblem(LocalProblem.Model.LSP, 256,
                List.of(new LocalProblem.Function("f", 0, 1, 1, 128, 1, 0.9, 0.1, 0, 0, 0, 0)));
        var solution = new LocalSolution(LocalSolution.Status.OPTIMAL, new double[]{0}, new double[]{0}, new double[]{0}, new int[]{2}, 0, 0, 0);
        return AuctionSnapshot.open("seller", "s-run", 1, 0, 1, START, START.plusSeconds(300), problem, solution,
                Map.of("f", new AuctionSnapshot.FunctionIdentity("image-v1", 7)), Map.of("a", "a-run", "b", "b-run"));
    }
    static AuctionMessage.Envelope envelope(String sender, String inc, long epoch, String id) {
        return new AuctionMessage.Envelope(1, sender, inc, epoch, 0, id, 1, START, START.plusSeconds(300));
    }
    AuctionMessage bid(String buyer, String id, long epoch, long quantity, double price, String version) {
        return AuctionMessage.bid(envelope(buyer, buyer + "-run", epoch, id), "seller",
                new AuctionMessage.Bid("f", version, 3, quantity, price, false));
    }
    @Test void duplicateAndOldBidsDoNotConsumeAnotherCommitment() {
        var ledger = new SellerLedger(state(), OneShotAuctionEngine.Options.base());
        var request = bid("a", "bid-1", 1, 2, 0.01, "image-v1");
        ledger.apply(request);
        assertThat(ledger.apply(request).state().pendingBids()).hasSize(1);
        assertThat(ledger.apply(bid("b", "old", 0, 2, 1, "image-v1")).state().pendingBids()).hasSize(1);
        var closed = ledger.apply(AuctionMessage.close(envelope("seller", "s-run", 1, "close"), "seller"));
        assertThat(closed.state().assignments().values()).hasSize(1);
        assertThat(closed.state().assignments().values().iterator().next().quantity()).isEqualTo(2);
        assertThat(closed.state().assignments().values().iterator().next().readyConfirmed()).isFalse();
    }
    @Test void closingRoundSortsBidsBeforeGrantingAndNeverOvercommitsOrReplaces() {
        var ledger = new SellerLedger(state(), OneShotAuctionEngine.Options.base());
        ledger.apply(bid("a", "a-bid", 1, 2, 0.01, "image-v1"));
        ledger.apply(bid("b", "b-bid", 1, 2, 0.02, "image-v1"));
        var closed = ledger.apply(AuctionMessage.close(envelope("seller", "s-run", 1, "close"), "seller"));
        assertThat(closed.state().assignments().values()).extracting(Assignment::buyerId).containsExactly("b");
        assertThat(closed.state().assignments().values()).extracting(Assignment::quantity).containsExactly(2L);
        assertThat(ledger.apply(bid("a", "late", 1, 2, 100, "image-v1")).state()).isEqualTo(closed.state());
    }
    @Test void versionMismatchRejectsButDifferentLocalGenerationIsCompatible() {
        var ledger = new SellerLedger(state(), OneShotAuctionEngine.Options.base());
        ledger.apply(bid("a", "bad", 1, 1, 1, "image-v2"));
        assertThat(ledger.snapshot().pendingBids()).isEmpty();
        ledger.apply(bid("a", "good", 1, 1, 1, "image-v1"));
        assertThat(ledger.snapshot().pendingBids()).hasSize(1);
    }
    @Test void readinessCanReduceProvisionalGrantButCannotExpandIt() {
        var ledger = new SellerLedger(state(), OneShotAuctionEngine.Options.base());
        ledger.apply(bid("a", "bid", 1, 2, 0.01, "image-v1"));
        ledger.apply(AuctionMessage.close(envelope("seller", "s-run", 1, "close"), "seller"));
        Assignment before = ledger.snapshot().assignments().values().iterator().next();
        var confirmed = before.confirm(1);
        var ready = new AuctionMessage(envelope("seller", "s-run", 1, "ready"), AuctionMessage.Kind.READY_CONFIRM, "seller", null, null, confirmed);
        ledger.apply(ready);
        assertThat(ledger.snapshot().assignments().get(before.id()).readyConfirmed()).isTrue();
        assertThat(ledger.snapshot().assignments().get(before.id()).quantity()).isEqualTo(1);
        var tooLarge = new Assignment(before.id(), before.buyerId(), before.buyerIncarnation(), before.sellerId(), before.sellerIncarnation(),
                before.function(), before.version(), before.sellerGeneration(), before.buyerGeneration(), before.epoch(), 3, true);
        var malformed = new AuctionMessage(envelope("seller", "s-run", 1, "invalid-ready"), AuctionMessage.Kind.READY_CONFIRM, "seller", null, null, tooLarge);
        assertThatCode(() -> ledger.apply(malformed)).doesNotThrowAnyException();
        assertThat(ledger.snapshot().assignments().get(before.id()).quantity()).isEqualTo(1);
    }
    @Test void nextRoundCannotReplacePreviousBuyerEvenWithHigherPrice() {
        var ledger = new SellerLedger(state(), OneShotAuctionEngine.Options.base());
        ledger.apply(bid("a", "first", 1, 2, 0.01, "image-v1"));
        ledger.apply(AuctionMessage.close(envelope("seller", "s-run", 1, "close"), "seller"));
        ledger.nextRound();
        var next = new AuctionMessage.Envelope(1, "b", "b-run", 1, 1, "better", 1, START, START.plusSeconds(300));
        ledger.apply(AuctionMessage.bid(next, "seller", new AuctionMessage.Bid("f", "image-v1", 99, 2, 100, false)));
        var close = new AuctionMessage.Envelope(1, "seller", "s-run", 1, 1, "close2", 1, START, START.plusSeconds(300));
        ledger.apply(AuctionMessage.close(close, "seller"));
        assertThat(ledger.snapshot().assignments().values()).extracting(Assignment::buyerId).containsExactly("a");
        assertThat(ledger.snapshot().assignments().values()).extracting(Assignment::quantity).containsExactly(2L);
    }
    @Test void finalizationDropsUnusedMemoryProposalsAndKeepsFixedTraffic() {
        var ledger = new SellerLedger(state(), OneShotAuctionEngine.Options.base());
        var result = ledger.finalizeAuction();
        assertThat(ledger.snapshot()).isSameAs(result.state());
        assertThat(result.state().desiredReplicas().get("f")).isZero();
        assertThat(result.state().assignments()).isEmpty();
        assertThat(result.state().status()).isEqualTo("FINALIZED");
    }
}
