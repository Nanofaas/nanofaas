package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

/**
 * Best-effort completion notifications from {@link AttemptCoordinator}.
 * Every call happens outside the execution record's monitor and only after the coordinator has
 * already confirmed the admitted generation is still current — an implementation never needs to
 * re-check that itself, and a failing implementation must not disturb dispatch or completion
 * (the coordinator calls these best-effort).
 *
 * <p>{@code submitted}/{@code retried} fire once per attempt, at the moment that attempt is
 * handed to the transport or republished after a retriable failure. {@code completed} carries
 * this invocation's own success/error outcome, and fires exactly once per invocation from
 * whichever of {@link AttemptCoordinator}'s own concluding actions decided it (a normal
 * completion, a retry that could not even be scheduled, an offloaded call's conclusion, or an
 * administrative expiry) — the passive case of an already-externally-terminated record being
 * settled again is NOT one of these, and does not call {@code completed}. {@code queueWaitNanos}
 * and {@code serviceNanos} are {@link #NOT_MEASURABLE} when a real dispatch happened but a
 * duration could not be computed, or {@link #NO_ATTEMPT} when this conclusion never dispatched
 * at all (a retry that never scheduled, an offload, an administrative expiry) — an implementation
 * should record cold/warm-start and the per-attempt timers only when neither is {@code
 * NO_ATTEMPT}, but should always record success/error from {@code result}.
 *
 * <p>{@code terminal} fires exactly once per invocation, from the single shared terminal
 * transition — normal completion, a sync waiter's timeout, administrative expiry, an offloaded
 * call's conclusion, or a queue-side termination the engine itself decided — regardless of which
 * of those it was, including the passive case {@code completed} does not cover. {@code
 * endToEndNanos} is the invocation's total, admission to conclusion; {@code result} is provided
 * for context but an implementation should NOT use it to record success/error — a passive
 * settle of a record this class never actively concluded (e.g. a sync-queue timeout) has no
 * success/error worth counting, only a duration.
 *
 * <p>Waiter/replay/admission-level observations (a caller's own timeout, a replayed idempotency
 * key, admission counters) are NOT part of this contract: those stay with the entry coordinator
 * that already owns them.
 */
public interface AttemptObserver {
    /** A real dispatch happened but this particular duration could not be computed. */
    long NOT_MEASURABLE = -1L;

    /** This conclusion never dispatched at all: skip cold/warm-start and per-attempt timers. */
    long NO_ATTEMPT = Long.MIN_VALUE;

    void submitted(InvocationTask task);

    void retried(InvocationTask task);

    void completed(InvocationTask task, DispatchResult result, long queueWaitNanos, long serviceNanos);

    void terminal(InvocationTask task, InvocationResult result, long endToEndNanos);
}
