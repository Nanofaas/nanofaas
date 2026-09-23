package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;

/**
 * Everything the engine needs from the execution lifecycle to turn a selected ticket into a
 * running attempt. Every method here is called <strong>outside</strong> the engine's gate, so an
 * implementation may take the execution record's monitor, the capacity registry's locks or any
 * other lifecycle lock; none of them may call back into the engine while holding one (ADR 0002 —
 * the gate is a leaf).
 */
public interface EngineDispatch {

    /**
     * Acquires the attempt's capacity lease, or returns {@code null} when the function is at its
     * limit. A returned lease belongs to the engine until {@link #submit} is called with it.
     */
    DispatchOwnership tryAcquire(SchedulingTicket ticket);

    /**
     * Whether this ticket still describes live work: the execution exists, is not terminal, and
     * is still at this attempt and generation. A {@code false} answer retires the ticket — the
     * engine drops it from the store and the index, releases its reservation and reports it
     * through {@link #removed}.
     *
     * <p>An implementation may conservatively answer {@code true} for every ticket, and the
     * shipped one (the control plane's {@code EngineTransport}) does exactly that. Staleness is
     * then owned by the dispatch path, not by this method: {@link #tryAcquire} acquires only
     * against the ticket's own generation, which the capacity registry refuses as soon as it is
     * retired, and the transport behind {@link #submit} fences on the execution's own attempt and
     * generation. This method is a fast path for a lifecycle that already knows a ticket is dead,
     * never the only thing standing between the engine and a stale dispatch: no caller may read it
     * as a staleness filter, and an implementation that answers {@code true} unconditionally is
     * conformant.
     */
    boolean isCurrent(SchedulingTicket ticket);

    /**
     * Hands the attempt, with its lease attached, to the transport. Non-blocking: it publishes
     * the dispatch and returns. Throwing {@link
     * it.unimib.datai.nanofaas.controlplane.capacity.InvocationQuotaExceededException} means
     * input backpressure and leaves the task eligible for requeue; any other throwable retires
     * the attempt through {@link #rejected}.
     */
    void submit(InvocationTask task);

    /** The ticket waited past its queue deadline and was dropped before dispatch. */
    void expired(InvocationTask task);

    /** Admitted work the engine lost: an out-of-band removal, or a ticket that is no longer current. */
    void removed(InvocationTask task);

    /** The submit of an already committed attempt failed; the engine has returned its lease. */
    void rejected(InvocationTask task, Throwable failure);
}
