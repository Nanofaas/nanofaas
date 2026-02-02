package it.unimib.datai.mcfaas.controlplane.sync;

import it.unimib.datai.mcfaas.controlplane.config.SyncQueueProperties;

import java.time.Instant;

public class SyncQueueAdmissionController {
    private final SyncQueueProperties props;
    private final WaitEstimator estimator;

    public SyncQueueAdmissionController(SyncQueueProperties props, WaitEstimator estimator) {
        this.props = props;
        this.estimator = estimator;
    }

    public SyncQueueAdmissionResult evaluate(String functionName, int depth, Instant now) {
        if (depth >= props.maxDepth()) {
            return SyncQueueAdmissionResult.rejected(SyncQueueRejectReason.DEPTH, Double.POSITIVE_INFINITY);
        }
        double estWaitSeconds = estimator.estimateWaitSeconds(functionName, depth, now);
        if (props.admissionEnabled() && estWaitSeconds > props.maxEstimatedWait().toSeconds()) {
            return SyncQueueAdmissionResult.rejected(SyncQueueRejectReason.EST_WAIT, estWaitSeconds);
        }
        return SyncQueueAdmissionResult.accepted(estWaitSeconds);
    }
}
