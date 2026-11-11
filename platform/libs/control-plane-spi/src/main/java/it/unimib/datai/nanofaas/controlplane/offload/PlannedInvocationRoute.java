package it.unimib.datai.nanofaas.controlplane.offload;
import java.util.Map;
/** One admission decision for the entire logical execution, including local retry attempts. */
public record PlannedInvocationRoute(Kind kind,String targetUrl,String executionNode,long epoch,String assignment,Map<String,String> headers,String reason) {
    public enum Kind { LEGACY,LOCAL,LOCAL_THEN_CLOUD,REMOTE,REJECT }
    public PlannedInvocationRoute { headers=headers==null?Map.of():Map.copyOf(headers); }
    public static PlannedInvocationRoute legacy() { return new PlannedInvocationRoute(Kind.LEGACY,null,null,0,null,Map.of(),null); }
    public static PlannedInvocationRoute local(String node) { return new PlannedInvocationRoute(Kind.LOCAL,null,node,0,null,Map.of(),null); }
    public static PlannedInvocationRoute localThenCloud(String node,PlannedInvocationRoute cloud) {
        if(cloud.kind()!=Kind.REMOTE || !"cloud".equals(cloud.executionNode())) throw new IllegalArgumentException("terminal cloud route required");
        return new PlannedInvocationRoute(Kind.LOCAL_THEN_CLOUD,cloud.targetUrl(),node,cloud.epoch(),cloud.assignment(),cloud.headers(),null);
    }
    public PlannedInvocationRoute cloudFallback() {
        if(kind!=Kind.LOCAL_THEN_CLOUD) throw new IllegalStateException("not a local/cloud route");
        return new PlannedInvocationRoute(Kind.REMOTE,targetUrl,"cloud",epoch,assignment,headers,null);
    }
    public static PlannedInvocationRoute reject(String reason) { return new PlannedInvocationRoute(Kind.REJECT,null,null,0,null,Map.of(),reason); }
}
