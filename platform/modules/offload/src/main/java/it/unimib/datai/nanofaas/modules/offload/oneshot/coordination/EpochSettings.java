package it.unimib.datai.nanofaas.modules.offload.oneshot.coordination;
import java.time.Duration;
public record EpochSettings(Duration auctionBudget,Duration peerTimeout,Duration solverBudget,int maxRounds,int parallelism,int queueCapacity,int maxPeers,double maxAuctionFraction) {
    public EpochSettings {
        for(var duration: new Duration[]{auctionBudget,peerTimeout,solverBudget}) if(duration==null || duration.isZero() || duration.isNegative() || duration.compareTo(Duration.ofHours(1))>0) throw new IllegalArgumentException("explicit positive bounded durations required");
        if(maxRounds<1 || maxRounds>1000 || parallelism<1 || parallelism>64 || queueCapacity<1 || queueCapacity>256 || maxPeers<1 || maxPeers>64 || queueCapacity<maxPeers*4 || !Double.isFinite(maxAuctionFraction) || maxAuctionFraction<=0 || maxAuctionFraction>0.2) throw new IllegalArgumentException("invalid epoch bounds");
    }
}
