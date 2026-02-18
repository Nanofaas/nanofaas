package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

public interface InvocationEnqueuer {

    boolean enqueue(InvocationTask task);

    boolean enabled();

    void decrementInFlight(String functionName);

    default boolean tryAcquireSlot(String functionName) {
        return true;
    }

    default void releaseSlot(String functionName) {
    }

    static InvocationEnqueuer noOp() {
        return NoOpInvocationEnqueuer.INSTANCE;
    }
}
