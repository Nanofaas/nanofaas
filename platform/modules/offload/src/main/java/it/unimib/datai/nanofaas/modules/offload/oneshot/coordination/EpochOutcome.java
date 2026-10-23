package it.unimib.datai.nanofaas.modules.offload.oneshot.coordination;
import it.unimib.datai.nanofaas.modules.offload.oneshot.auction.AuctionSnapshot;
public record EpochOutcome(long epoch,Status status,AuctionSnapshot snapshot,long auctionNanos,long solverNanos,int rounds,String reason,EpochInput input,EpochTiming timing) {
    public EpochOutcome(long epoch,Status status,AuctionSnapshot snapshot,long auctionNanos,long solverNanos,int rounds,String reason,EpochInput input) { this(epoch,status,snapshot,auctionNanos,solverNanos,rounds,reason,input,null); }
    public EpochOutcome(long epoch,Status status,AuctionSnapshot snapshot,long auctionNanos,long solverNanos,int rounds,String reason) { this(epoch,status,snapshot,auctionNanos,solverNanos,rounds,reason,null); }
    public enum Status { CONVERGED,FIXED_ROUNDS_COMPLETED,ROUND_LIMIT,DEADLINE,FAILED }
    public boolean censored() { return status!=Status.CONVERGED && status!=Status.FIXED_ROUNDS_COMPLETED; }
}
