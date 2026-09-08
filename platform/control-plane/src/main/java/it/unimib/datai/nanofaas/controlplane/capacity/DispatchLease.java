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
 * <p>The {@code generation} is an internal identity (see
 * {@link FunctionCapacityRegistry}); it is what keeps an old lease from
 * decrementing a re-registered function's state while it drains.
 */
public final class DispatchLease {
    private final String functionName;
    private final long generation;
    private final Runnable releaseAction;
    private final AtomicBoolean released = new AtomicBoolean(false);

    DispatchLease(String functionName, long generation, Runnable releaseAction) {
        this.functionName = functionName;
        this.generation = generation;
        this.releaseAction = releaseAction;
    }

    public String functionName() {
        return functionName;
    }

    /** Internal generation identity; never part of any public key or tag. */
    public long generation() {
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
