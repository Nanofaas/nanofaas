package it.unimib.datai.nanofaas.execution.admission;

import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason;

public record SyncQueueAdmissionResult(
        boolean accepted,
        SyncQueueRejectReason reason,
        double estimatedWaitSeconds
) {
    public static SyncQueueAdmissionResult accepted(double estWaitSeconds) {
        return new SyncQueueAdmissionResult(true, null, estWaitSeconds);
    }

    public static SyncQueueAdmissionResult rejected(SyncQueueRejectReason reason, double estWaitSeconds) {
        return new SyncQueueAdmissionResult(false, reason, estWaitSeconds);
    }
}
