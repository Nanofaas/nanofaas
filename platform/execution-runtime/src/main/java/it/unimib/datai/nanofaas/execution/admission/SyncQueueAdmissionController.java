package it.unimib.datai.nanofaas.execution.admission;

import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason;

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
        // Read both correlated runtime settings from ONE published snapshot so a
        // concurrent runtime-config apply/restore can never leave this decision on a
        // partial combination (new admissionEnabled with a stale maxEstimatedWait, or
        // vice versa).
        var runtime = configSource.syncQueueRuntimeDefaults();
        long maxWaitSeconds = runtime.maxEstimatedWait().toSeconds();
        if (runtime.admissionEnabled() && (maxWaitSeconds == 0 || estWaitSeconds > maxWaitSeconds)) {
            return SyncQueueAdmissionResult.rejected(SyncQueueRejectReason.EST_WAIT, estWaitSeconds);
        }
        return SyncQueueAdmissionResult.accepted(estWaitSeconds);
    }
}
