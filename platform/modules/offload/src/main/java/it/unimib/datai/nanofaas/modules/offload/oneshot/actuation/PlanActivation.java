package it.unimib.datai.nanofaas.modules.offload.oneshot.actuation;
import java.time.Instant;
import java.util.Map;
import java.util.List;
public record PlanActivation(Status status,ActiveRoutingPlan plan,Map<String,Integer> desiredReplicas,Map<String,Integer> readyReplicas,String reason,Instant preparedAt,List<AssignmentDiagnostic> diagnostics) {
    public enum Status { NEGOTIATED,PREPARED,DEGRADED,FAILED }
    public enum ConfirmationReason { CONFIRMED,PARTIAL_READY,TIMEOUT,PEER_REFUSED,TRANSPORT_ERROR,READY_NOT_RECEIVED,ACK_NOT_RECEIVED,NOT_ATTEMPTED_TIME_WINDOW }
    /** Observations at this node, never a claim that a timed-out packet was not delivered. */
    public record AssignmentDiagnostic(Instant observedAtUtc,double monotonicOffsetSeconds,String sessionId,
            String nodeId,long epoch,int round,String assignmentId,String function,String version,
            long buyerGeneration,long sellerGeneration,String buyerId,String buyerIncarnation,
            String sellerId,String sellerIncarnation,long negotiatedQuantity,long confirmedQuantity,
            boolean readyReceived,boolean ackSent,boolean ackReceived,boolean ackResponseReceived,
            ConfirmationReason reason,double residualCloudRate) {}
    public PlanActivation(Status status,ActiveRoutingPlan plan,Map<String,Integer> desired,Map<String,Integer> ready,String reason,Instant at) {
        this(status,plan,desired,ready,reason,at,List.of());
    }
    public PlanActivation { desiredReplicas=Map.copyOf(desiredReplicas); readyReplicas=Map.copyOf(readyReplicas); diagnostics=List.copyOf(diagnostics); }
}
