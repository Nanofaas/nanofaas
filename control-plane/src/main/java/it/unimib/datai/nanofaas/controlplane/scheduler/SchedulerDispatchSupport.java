package it.unimib.datai.nanofaas.controlplane.scheduler;

import org.slf4j.Logger;

public final class SchedulerDispatchSupport {
    private SchedulerDispatchSupport() {
    }

    public static void dispatchWithFailureCleanup(InvocationTask task,
                                                  Runnable dispatchAction,
                                                  Runnable failureCleanup,
                                                  Logger log) {
        try {
            dispatchAction.run();
        } catch (Exception ex) {
            failureCleanup.run();
            log.error("Dispatch failed for execution {}: {}", task.executionId(), ex.getMessage(), ex);
        }
    }
}
