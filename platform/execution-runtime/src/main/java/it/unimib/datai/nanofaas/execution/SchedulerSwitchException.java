package it.unimib.datai.nanofaas.execution;

import java.util.Objects;

/**
 * A scheduler switch that was refused <strong>before</strong> its commit point. The previous
 * strategy is still active, its index was never mutated and every reservation, claim and lease
 * is exactly where it was; the caller may retry.
 *
 * <p>Nothing throws this after the commit: publishing the new index is the linearization point,
 * and the cleanup that follows it (discarding the superseded index) can never undo it.
 */
public class SchedulerSwitchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the switch was refused, so an admin API can map it to a status without parsing text. */
    public enum Reason {
        /** The target strategy could not build or populate its index. */
        PREPARATION,
        /** More pending work than one switch may rebuild under the engine's gate; retry later. */
        TEMPORARY_CAP,
        /** The rebuild outran its budget and was abandoned; retry later. */
        TIMEOUT
    }

    private final Reason reason;

    public SchedulerSwitchException(Reason reason, String message) {
        this(reason, message, null);
    }

    public SchedulerSwitchException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public Reason reason() {
        return reason;
    }
}
