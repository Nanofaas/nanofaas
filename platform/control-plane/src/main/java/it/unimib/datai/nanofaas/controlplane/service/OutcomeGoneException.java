package it.unimib.datai.nanofaas.controlplane.service;

/**
 * The idempotency key is still bound to an execution that completed, but the
 * execution's outcome payload was evicted for capacity before its retention
 * window ended. The function is <b>not</b> re-invoked: the deduplication
 * guarantee holds, and the replay is served as {@code HTTP 410 Gone} instead.
 */
public final class OutcomeGoneException extends RuntimeException {
    private final String executionId;

    public OutcomeGoneException(String executionId) {
        super("Invocation outcome for execution " + executionId + " is no longer available");
        this.executionId = executionId;
    }

    public String executionId() {
        return executionId;
    }
}
