package it.unimib.datai.nanofaas.controlplane.sync;

public class SyncQueueRejectedException extends RuntimeException {
    private final SyncQueueRejectReason reason;
    private final int retryAfterSeconds;

    // ponytail: no message, no cause, no suppression, no stack trace. This is a
    // control-flow signal that becomes a 429 - nobody ever reads its stack, and
    // filling one in costs a walk of a Reactor stack per rejected request.
    public SyncQueueRejectedException(SyncQueueRejectReason reason, int retryAfterSeconds) {
        super(null, null, false, false);
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
