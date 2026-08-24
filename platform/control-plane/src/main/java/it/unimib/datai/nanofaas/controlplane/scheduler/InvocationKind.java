package it.unimib.datai.nanofaas.controlplane.scheduler;

/**
 * Which door an invocation came in by, carried on the task because the queue is shared.
 *
 * With sync-queue off and async-queue on — the configuration every comparison runs —
 * ReactiveInvocationCoordinator.admitLocally and InvocationService.invokeAsync make the
 * identical enqueue call, so both kinds land in one FunctionQueueState: one bounded
 * queue, one inFlight counter, one set of concurrency slots. Every per-function meter
 * therefore reports a mixture, and the question "did async work displace a caller that
 * was waiting" has no answer in the data.
 *
 * The distinction that matters is not the endpoint but the deadline: a SYNC caller holds
 * a connection open and times out, an ASYNC one has already been handed its 202 and has
 * nobody waiting. The scheduler only ever sees the task it polled, which is why the bit
 * travels here rather than on the execution record.
 */
public enum InvocationKind {
    SYNC,
    ASYNC;

    /** Lower-case, because it is used as a metric tag value. */
    public String tag() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
