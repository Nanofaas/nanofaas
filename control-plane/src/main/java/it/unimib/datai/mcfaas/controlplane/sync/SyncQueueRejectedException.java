package it.unimib.datai.mcfaas.controlplane.sync;

public class SyncQueueRejectedException extends RuntimeException {
    private final SyncQueueRejectReason reason;
    private final int retryAfterSeconds;

    public SyncQueueRejectedException(SyncQueueRejectReason reason, int retryAfterSeconds) {
        this.reason = reason;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public SyncQueueRejectReason reason() {
        return reason;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
