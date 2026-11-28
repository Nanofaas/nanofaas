package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;

import java.util.concurrent.Future;

/**
 * What the current attempt of an execution holds: its capacity lease and its cancellable
 * transport. Not thread-safe on its own: every call runs under the owning {@link ExecutionRecord}'s
 * monitor, which keeps these fields consistent with the record's state.
 */
final class AttemptOwnership {
    /**
     * The capacity lease the current attempt owns, set at dispatch and released exactly once by the
     * attempt's completion (invariant I4). Null for a path that never acquired local capacity
     * (offload). Physical dispatch, once started, owns the release.
     */
    private DispatchOwnership lease;

    /**
     * The generation the execution was admitted under. Unlike {@link #lease} it survives the lease's
     * release and every retry, so a late listener still fences against the identity the attempt was
     * admitted under (I7).
     */
    private FunctionGeneration admittedGeneration;

    /**
     * The raw cancellable transport handle of the current attempt (the dispatch future itself, not
     * a composed one). Administrative expiry requests cancellation on it, so the work and its payload
     * do not outlive the released capacity.
     */
    private Future<?> handle;
    private boolean cancellationRequested;
    private boolean transportOwnsCapacity;

    AttemptOwnership(FunctionGeneration admittedGeneration) {
        this.admittedGeneration = admittedGeneration;
    }

    void attachLease(DispatchOwnership acquired) {
        this.lease = acquired;
        if (admittedGeneration == null) {
            admittedGeneration = acquired.generation();
        }
    }

    DispatchOwnership takeLease() {
        DispatchOwnership taken = lease;
        lease = null;
        return taken;
    }

    boolean holdsLease() {
        return lease != null;
    }

    FunctionGeneration admittedGeneration() {
        return admittedGeneration;
    }

    /**
     * Records the transport handle; returns true, keeping nothing, when cancellation was requested
     * before the handle arrived, so the caller cancels it right away.
     */
    boolean attachHandle(Future<?> attached) {
        if (cancellationRequested) {
            return true;
        }
        handle = attached;
        return false;
    }

    /** Requests cancellation, remembered for a handle not attached yet, and hands back the current one. */
    Future<?> requestCancellation() {
        cancellationRequested = true;
        Future<?> taken = handle;
        handle = null;
        return taken;
    }

    void transportOwnsCapacity() {
        transportOwnsCapacity = true;
    }

    boolean capacityOwnedByTransport() {
        return transportOwnsCapacity;
    }

    /**
     * A retry is a fresh attempt that re-acquires at its own dispatch: the failed attempt's lease and
     * handle were released by its completion, and a stale handle must never be cancelled on the next
     * attempt's behalf. The admitted generation stays.
     */
    void resetForRetry() {
        lease = null;
        handle = null;
        cancellationRequested = false;
        transportOwnsCapacity = false;
    }
}
