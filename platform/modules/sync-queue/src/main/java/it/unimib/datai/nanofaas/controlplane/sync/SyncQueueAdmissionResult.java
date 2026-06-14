package it.unimib.datai.nanofaas.controlplane.sync;

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
