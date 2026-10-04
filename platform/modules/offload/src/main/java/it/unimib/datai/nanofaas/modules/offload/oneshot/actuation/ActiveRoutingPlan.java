package it.unimib.datai.nanofaas.modules.offload.oneshot.actuation;
import it.unimib.datai.nanofaas.modules.offload.oneshot.auction.Assignment;
import java.time.Instant;
import java.util.*;
/** Ready capacity, never desired capacity, authorizes routing within one immutable epoch. */
public record ActiveRoutingPlan(String nodeId,String incarnation,long epoch,long revision,Instant startsAt,Instant endsAt,double flowQuantum,Map<String,FunctionPlan> functions) {
    public record FunctionPlan(String version,long generation,int readyReplicas,double localRate,double cloudRate,double forecastRate,long memoryMiB,double demandSeconds,double utilization,List<Assignment> inbound,List<Assignment> outbound) {
        public FunctionPlan {
            inbound=List.copyOf(inbound); outbound=List.copyOf(outbound);
            if(version==null || version.isBlank() || generation<1 || readyReplicas<0 || memoryMiB<1 || !Double.isFinite(demandSeconds) || demandSeconds<=0 || !Double.isFinite(utilization) || utilization<=0 || utilization>1) throw new IllegalArgumentException("invalid function plan");
            for(double rate:new double[]{localRate,cloudRate,forecastRate}) if(!Double.isFinite(rate) || rate<0) throw new IllegalArgumentException("invalid routing rate");
            if(readyReplicas==0 && (localRate>0 || inbound.stream().anyMatch(a->a.quantity()>0))) throw new IllegalArgumentException("zero ready capacity cannot route locally");
            if(inbound.stream().anyMatch(a->!a.readyConfirmed()) || outbound.stream().anyMatch(a->!a.readyConfirmed())) throw new IllegalArgumentException("provisional grants cannot route");
        }
    }
    public ActiveRoutingPlan {
        if(nodeId==null || incarnation==null || epoch<0 || revision<1 || startsAt==null || endsAt==null || !startsAt.isBefore(endsAt) || !Double.isFinite(flowQuantum) || flowQuantum<=0) throw new IllegalArgumentException("invalid active plan");
        functions=Map.copyOf(functions);
        for(var entry:functions.entrySet()) {
            var f=entry.getValue();
            double inbound=f.inbound().stream().mapToLong(Assignment::quantity).sum()*flowQuantum;
            double outbound=f.outbound().stream().mapToLong(Assignment::quantity).sum()*flowQuantum;
            if(inbound+f.localRate()>f.readyReplicas()*f.utilization()/f.demandSeconds()+1e-9 || f.localRate()+outbound+f.cloudRate()>f.forecastRate()+1e-9) throw new IllegalArgumentException("infeasible ready plan");
            for(var a:f.inbound()) if(a.epoch()!=epoch || !a.function().equals(entry.getKey()) || !a.version().equals(f.version()) || !a.sellerId().equals(nodeId) || !a.sellerIncarnation().equals(incarnation) || a.sellerGeneration()!=f.generation()) throw new IllegalArgumentException("inbound identity mismatch");
            for(var a:f.outbound()) if(a.epoch()!=epoch || !a.function().equals(entry.getKey()) || !a.version().equals(f.version()) || !a.buyerId().equals(nodeId) || !a.buyerIncarnation().equals(incarnation) || a.buyerGeneration()!=f.generation()) throw new IllegalArgumentException("outbound identity mismatch");
        }
    }
    public boolean validAt(Instant instant) { return !instant.isBefore(startsAt) && instant.isBefore(endsAt); }
}
