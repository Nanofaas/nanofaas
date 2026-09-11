package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationDispatch;

/**
 * Bounded retry-only scheduling for the no-queue profile. Initial admission is
 * direct and public async submission is unavailable, independently of this port.
 * Each attempt acquires capacity and transports that same handle to physical dispatch.
 * Submission is always queued on the bounded executor so synchronously completed
 * retries cannot recursively grow the stack. Refusal concludes through the existing
 * completion handler, just like a full function queue.
 */
final class ExecutorBackedInvocationEnqueuer implements RetryScheduler {
    private static final Logger log = LoggerFactory.getLogger(ExecutorBackedInvocationEnqueuer.class);

    private final InvocationDispatch dispatch;
    private final FunctionCapacityRegistry capacityRegistry;
    private final ExecutorService executor;

    ExecutorBackedInvocationEnqueuer(InvocationDispatch dispatch,
                                     FunctionCapacityRegistry capacityRegistry,
                                     ExecutorService executor) {
        this.dispatch = dispatch;
        this.capacityRegistry = capacityRegistry;
        this.executor = executor;
    }

    @Override
    public boolean enqueue(InvocationTask task) {
        DispatchOwnership lease = capacityRegistry.tryAcquireLease(
                task.functionName(), task.functionSpec().concurrency());
        if (lease == null) {
            log.warn("Retry refused for execution {} (function {}, attempt {}): no capacity",
                    task.executionId(), task.functionName(), task.attempt());
            return false;
        }
        try {
            executor.execute(() -> dispatch.dispatch(task.withDispatchLease(lease)));
            return true;
        } catch (RuntimeException | Error ex) {
            lease.release();
            log.warn("Retry scheduling rejected for execution {} (function {}, attempt {}): {}",
                    task.executionId(), task.functionName(), task.attempt(), ex.toString());
            return false;
        }
    }

    /** Spring destroy-method hook: stop accepting new retries and let in-flight ones finish. */
    void shutdown() {
        executor.shutdown();
    }
}
