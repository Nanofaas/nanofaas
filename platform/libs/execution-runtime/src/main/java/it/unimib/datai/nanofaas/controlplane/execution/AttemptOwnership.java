package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;

import java.util.concurrent.Future;

/**
 * What the current attempt of an execution holds: its cancellable transport, and whether that
 * transport owns the attempt's capacity. Not thread-safe on its own: every call runs under the
 * owning {@link ExecutionRecord}'s monitor, which keeps these fields consistent with the record's
 * state. The capacity lease itself travels with the attempt's transport, which releases it once it
 * has really drained (invariant I4): the record never holds it.
 */
final class AttemptOwnership {
    /**
     * The generation the execution was admitted under. It survives every retry, so a late listener
     * still fences against the identity the attempt was admitted under (I7).
     */
    private final FunctionGeneration admittedGeneration;

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
     * A retry is a fresh attempt that re-acquires at its own dispatch: a stale handle must never be
     * cancelled on the next attempt's behalf. The admitted generation stays.
     */
    void resetForRetry() {
        handle = null;
        cancellationRequested = false;
        transportOwnsCapacity = false;
    }
}
