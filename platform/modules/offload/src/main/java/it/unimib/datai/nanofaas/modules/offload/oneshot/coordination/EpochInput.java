package it.unimib.datai.nanofaas.modules.offload.oneshot.coordination;
import it.unimib.datai.nanofaas.modules.offload.oneshot.solver.LocalProblem;
import it.unimib.datai.nanofaas.modules.offload.oneshot.auction.AuctionSnapshot;
import it.unimib.datai.nanofaas.forecastingapi.ForecastSnapshot;
import java.time.Instant;
import java.util.Map;
/** The factory freezes catalog, profiles and forecasts together, before negotiation starts. */
public record EpochInput(LocalProblem problem,Map<String,AuctionSnapshot.FunctionIdentity> identities,Map<String,ForecastSnapshot> forecasts,long revision,double flowQuantum,EpochSettings negotiation,long maxSolverStates,long maxSolverBytes,String profileHash) {
    public EpochInput(LocalProblem problem,Map<String,AuctionSnapshot.FunctionIdentity> identities,Map<String,ForecastSnapshot> forecasts,long revision,double flowQuantum) { this(problem,identities,forecasts,revision,flowQuantum,null,2000000,64L*1024*1024,""); }
    public EpochInput(LocalProblem problem,Map<String,AuctionSnapshot.FunctionIdentity> identities,Map<String,ForecastSnapshot> forecasts,long revision) { this(problem,identities,forecasts,revision,1,null,2000000,64L*1024*1024,""); }
    public EpochInput { identities=Map.copyOf(identities); forecasts=Map.copyOf(forecasts); if(profileHash==null || maxSolverStates<1 || maxSolverBytes<1 || revision<1 || !Double.isFinite(flowQuantum) || flowQuantum<=0) throw new IllegalArgumentException("positive revision required"); }
    @FunctionalInterface public interface Factory { EpochInput freeze(long epoch,Instant startsAt,Instant endsAt); }
}
