package it.unimib.datai.nanofaas.modules.offload.oneshot.coordination;
import it.unimib.datai.nanofaas.modules.offload.oneshot.auction.AuctionSnapshot;
public record EpochOutcome(long epoch,Status status,AuctionSnapshot snapshot,long auctionNanos,long solverNanos,int rounds,String reason) {
    public enum Status { CONVERGED,ROUND_LIMIT,DEADLINE,FAILED }
    public boolean censored() { return status!=Status.CONVERGED; }
}
