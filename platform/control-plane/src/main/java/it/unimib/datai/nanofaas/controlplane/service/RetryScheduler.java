package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

/** Schedules an already admitted execution's next attempt; grants no public admission capability. */
@FunctionalInterface
public interface RetryScheduler {
    boolean enqueue(InvocationTask task);

    static RetryScheduler unavailable() {
        return task -> false;
    }
}
