package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

/** Schedules an already admitted execution's next attempt; grants no public admission capability. */
@FunctionalInterface
public interface RetryScheduler {
    /** False/throw leaves cleanup with the caller; after true, late refusal calls onRejected once. */
    boolean enqueue(InvocationTask task, java.time.Instant notBefore, Runnable onRejected);

    static RetryScheduler unavailable() {
        return (task, notBefore, onRejected) -> false;
    }
}
