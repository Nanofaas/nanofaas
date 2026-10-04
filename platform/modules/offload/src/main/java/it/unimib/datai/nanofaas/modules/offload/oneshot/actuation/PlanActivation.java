package it.unimib.datai.nanofaas.modules.offload.oneshot.actuation;
import java.time.Instant;
import java.util.Map;
public record PlanActivation(Status status,ActiveRoutingPlan plan,Map<String,Integer> desiredReplicas,Map<String,Integer> readyReplicas,String reason,Instant preparedAt) {
    public enum Status { PREPARED,DEGRADED,FAILED }
    public PlanActivation { desiredReplicas=Map.copyOf(desiredReplicas); readyReplicas=Map.copyOf(readyReplicas); }
}
