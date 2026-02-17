package it.unimib.datai.nanofaas.controlplane.sync;

import it.unimib.datai.nanofaas.controlplane.config.runtime.RuntimeConfigService;
import it.unimib.datai.nanofaas.controlplane.config.runtime.RuntimeConfigSnapshot;

import java.time.Instant;

public class SyncQueueAdmissionController {
    private final RuntimeConfigService runtimeConfigService;
    private final WaitEstimator estimator;
    private final int maxDepth;

    public SyncQueueAdmissionController(RuntimeConfigService runtimeConfigService, int maxDepth, WaitEstimator estimator) {
        this.runtimeConfigService = runtimeConfigService;
        this.maxDepth = maxDepth;
        this.estimator = estimator;
    }

    public SyncQueueAdmissionResult evaluate(String functionName, int depth, Instant now) {
        if (depth >= maxDepth) {
            return SyncQueueAdmissionResult.rejected(SyncQueueRejectReason.DEPTH, Double.POSITIVE_INFINITY);
        }
        RuntimeConfigSnapshot config = runtimeConfigService.getSnapshot();
        double estWaitSeconds = estimator.estimateWaitSeconds(functionName, depth, now);
        long maxWaitSeconds = config.syncQueueMaxEstimatedWait().toSeconds();
        if (config.syncQueueAdmissionEnabled() && (maxWaitSeconds == 0 || estWaitSeconds > maxWaitSeconds)) {
            return SyncQueueAdmissionResult.rejected(SyncQueueRejectReason.EST_WAIT, estWaitSeconds);
        }
        return SyncQueueAdmissionResult.accepted(estWaitSeconds);
    }
}
