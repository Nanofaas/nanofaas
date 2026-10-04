package it.unimib.datai.nanofaas.modules.offload.oneshot.api;
import java.net.URI;
import java.time.*;
import java.util.*;
/** Immutable next-epoch configuration; physical resource and measurement identity remain explicit. */
public record OneShotSettings(int schemaVersion,String profileId,String environmentFingerprint,String purpose,boolean allowSynthetic,
        URI cloudUri,long memoryCapacityMiB,double flowQuantum,Duration period,Duration leadTime,boolean scheduled,Instant anchor,
        Duration preparationBudget,double maxOperationalFraction,int burst,Map<String,Function> functions,it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.EpochSettings negotiation,long maxSolverStates,long maxSolverBytes) {
    public OneShotSettings(int version,String profile,String environment,String purpose,boolean synthetic,URI cloud,long memory,double q,Duration period,Duration lead,boolean scheduled,Instant anchor,Duration preparation,double fraction,int burst,Map<String,Function> functions) {
        this(version,profile,environment,purpose,synthetic,cloud,memory,q,period,lead,scheduled,anchor,preparation,fraction,burst,functions,new it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.EpochSettings(Duration.ofSeconds(5),Duration.ofSeconds(1),Duration.ofSeconds(1),100,4,64,16,.1),2000000,64L*1024*1024);
    }
    public record Function(long generation,String imageDigest,String inputHash,double utilization,double alpha,double delta,double gamma) {
        public Function {
            if(generation<1 || !digest(imageDigest) || !digest(inputHash) || !Double.isFinite(utilization) || utilization<=0 || utilization>1) throw new IllegalArgumentException("invalid function identity or utilization");
            for(double x:new double[]{alpha,delta,gamma}) if(!Double.isFinite(x) || x<0) throw new IllegalArgumentException("invalid utility");
        }
    }
    public static boolean digest(String s) { return s!=null && s.matches("sha256:[0-9a-f]{64}"); }
    public OneShotSettings {
        if(schemaVersion!=1 || profileId==null || profileId.isBlank() || profileId.length()>256 || !digest(environmentFingerprint) || !Set.of("workflow-validation","scientific-experiment").contains(purpose)
            || cloudUri==null || !Set.of("http","https").contains(cloudUri.getScheme()) || cloudUri.getHost()==null || cloudUri.getUserInfo()!=null || cloudUri.getQuery()!=null || cloudUri.getFragment()!=null
            || negotiation==null || maxSolverStates<1 || maxSolverStates>10000000 || maxSolverBytes<1 || maxSolverBytes>256L*1024*1024
            || memoryCapacityMiB<1 || memoryCapacityMiB>Integer.MAX_VALUE || !Double.isFinite(flowQuantum) || flowQuantum<=0 || period==null || period.isNegative() || period.isZero() || period.compareTo(Duration.ofHours(24))>0
            || leadTime==null || leadTime.isNegative() || leadTime.isZero() || leadTime.compareTo(period)>=0 || preparationBudget==null || preparationBudget.isNegative() || preparationBudget.isZero() || preparationBudget.compareTo(leadTime)>=0
            || !Double.isFinite(maxOperationalFraction) || maxOperationalFraction<=0 || maxOperationalFraction>.2 || anchor==null || burst<1 || burst>1000 || functions==null || functions.isEmpty() || functions.size()>128)
            throw new IllegalArgumentException("invalid one-shot configuration");
        functions=Map.copyOf(functions);
        functions.forEach((id,f)-> { if(id==null || id.isBlank() || id.length()>256 || f==null) throw new IllegalArgumentException("invalid function"); });
    }
}
