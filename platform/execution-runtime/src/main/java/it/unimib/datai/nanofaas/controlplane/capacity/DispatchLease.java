package it.unimib.datai.nanofaas.controlplane.capacity;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The capacity an attempt acquired at dispatch, owned by that attempt and by the
 * function generation it was admitted under (ADR 0001 invariant I4).
 *
 * <p>A lease is the only thing a completion path may release: there is no
 * name-based release any more on the core dispatch path. Releasing is idempotent,
 * so a late callback, a retry reset, an administrative expiry and the normal
 * completion racing each other can only ever give the slot back once.
 *
 * <p>The {@link FunctionGeneration} is the internal identity of the incarnation
 * the attempt was admitted under (see {@link FunctionCapacityRegistry}); it is
 * what keeps an old lease from decrementing a re-registered function's state
 * while it drains.
 */
public final class DispatchLease implements DispatchOwnership {
    private final FunctionGeneration generation;
    private final Runnable releaseAction;
    private final AtomicBoolean released = new AtomicBoolean(false);

    DispatchLease(FunctionGeneration generation, Runnable releaseAction) {
        this.generation = generation;
        this.releaseAction = releaseAction;
    }

    public String functionName() {
        return generation.functionName();
    }

    /** Internal generation identity; never part of any public key or tag. */
    public FunctionGeneration generation() {
        return generation;
    }

    /** Gives the acquired capacity back, at most once. */
    public void release() {
        if (released.compareAndSet(false, true)) {
            releaseAction.run();
        }
    }

    public boolean isReleased() {
        return released.get();
    }
}
