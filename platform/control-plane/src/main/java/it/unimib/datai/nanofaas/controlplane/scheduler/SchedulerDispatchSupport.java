package it.unimib.datai.nanofaas.controlplane.scheduler;

import it.unimib.datai.nanofaas.controlplane.capacity.InvocationQuotaExceededException;
import org.slf4j.Logger;

public final class SchedulerDispatchSupport {
    private SchedulerDispatchSupport() {
    }

    public static Result dispatchWithFailureCleanup(InvocationTask task,
                                                    Runnable dispatchAction,
                                                    Runnable failureCleanup,
                                                    java.util.function.Consumer<Throwable> rejected,
                                                    Logger log) {
        try {
            dispatchAction.run();
            return Result.DISPATCHED;
        } catch (InvocationQuotaExceededException ex) {
            failureCleanup.run();
            log.debug("Input capacity blocked dispatch for execution {}", task.executionId());
            return Result.INPUT_BACKPRESSURED;
        } catch (RuntimeException | Error ex) {
            try { failureCleanup.run(); }
            finally { rejected.accept(ex); }
            log.error("Dispatch failed for execution {}: {}", task.executionId(), ex.getMessage(), ex);
            return Result.FAILED;
        }
    }

    public enum Result {
        DISPATCHED,
        INPUT_BACKPRESSURED,
        FAILED
    }
}
