package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

public interface InvocationEnqueuer {

    boolean enqueue(InvocationTask task);

    boolean enabled();

    default boolean hasAvailableSlot(String functionName) {
        return true;
    }

    /**
     * Whether {@link #enqueue} would certainly refuse a task right now. A hint:
     * `enqueue` stays the authority.
     *
     * <p>Phrased negatively on purpose. Mockito does not run a default method, it
     * returns false for an unstubbed boolean - so a test that never heard of this
     * method gets "not full", which is the answer that leaves behaviour unchanged.
     * The positive spelling made every such mock report an empty queue as full.
     */
    default boolean isQueueFull(String functionName) {
        return false;
    }

    default boolean tryAcquireSlot(String functionName) {
        return true;
    }

    default void releaseDispatchSlot(String functionName) {
    }

    static InvocationEnqueuer noOp() {
        return NoOpInvocationEnqueuer.INSTANCE;
    }
}
