package it.unimib.datai.nanofaas.modules.offload.oneshot.auction;
/** Seller authority applies a complete immutable transition before making replies observable. */
public final class SellerLedger {
    private AuctionSnapshot state;
    private final OneShotAuctionEngine engine;
    public SellerLedger(AuctionSnapshot state, OneShotAuctionEngine.Options options) { this.state = state; engine = new OneShotAuctionEngine(options); }
    public SellerLedger(AuctionSnapshot state, OneShotAuctionEngine engine) { this.state=state; this.engine=engine; }
    public synchronized AuctionTransition apply(AuctionMessage message) {
        AuctionTransition transition = engine.advance(state, message);
        state = transition.state();
        return transition;
    }
    public synchronized AuctionSnapshot snapshot() { return state; }
    public synchronized AuctionTransition finalizeAuction() {
        AuctionTransition transition = engine.finalizeAuction(state);
        state = transition.state();
        return transition;
    }
    public synchronized void nextRound() { state = state.nextRound(); }
}
