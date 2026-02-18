package it.unimib.datai.nanofaas.controlplane.sync;

import java.time.Instant;

public class SyncQueueAdmissionController {
    private final SyncQueueConfigSource configSource;
    private final WaitEstimator estimator;
    private final int maxDepth;

    public SyncQueueAdmissionController(SyncQueueConfigSource configSource, int maxDepth, WaitEstimator estimator) {
        this.configSource = configSource;
        this.maxDepth = maxDepth;
        this.estimator = estimator;
    }

    public SyncQueueAdmissionResult evaluate(String functionName, int depth, Instant now) {
        if (depth >= maxDepth) {
            return SyncQueueAdmissionResult.rejected(SyncQueueRejectReason.DEPTH, Double.POSITIVE_INFINITY);
        }
        double estWaitSeconds = estimator.estimateWaitSeconds(functionName, depth, now);
        long maxWaitSeconds = configSource.syncQueueMaxEstimatedWait().toSeconds();
        if (configSource.syncQueueAdmissionEnabled() && (maxWaitSeconds == 0 || estWaitSeconds > maxWaitSeconds)) {
            return SyncQueueAdmissionResult.rejected(SyncQueueRejectReason.EST_WAIT, estWaitSeconds);
        }
        return SyncQueueAdmissionResult.accepted(estWaitSeconds);
    }
}
