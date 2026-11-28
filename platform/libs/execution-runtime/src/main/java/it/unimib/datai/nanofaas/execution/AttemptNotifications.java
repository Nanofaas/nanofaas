package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link AttemptObserver} as the attempt path calls it. Every notification is best-effort: an
 * observer that throws is logged and never interrupts the attempt. {@link #isCurrentGeneration} is
 * the fence callers apply before attributing an outcome to a function's meters (I7): metrics
 * recording belongs to the observer, the generation decision to the capacity registry.
 */
final class AttemptNotifications {
    private static final Logger log = LoggerFactory.getLogger(AttemptNotifications.class);

    private final AttemptObserver observer;
    private final FunctionCapacityRegistry capacity;

    AttemptNotifications(AttemptObserver observer, FunctionCapacityRegistry capacity) {
        this.observer = observer;
        this.capacity = capacity;
    }

    /**
     * True when a completion may still attribute to {@code functionName}'s meters: the generation
     * captured at admission is still the one the capacity registry considers active, or the
     * function has no active generation at all. False only when the function was removed and
     * re-registered under a new generation since admission (I7).
     */
    boolean isCurrentGeneration(String functionName, FunctionGeneration admittedGeneration) {
        FunctionGeneration active = capacity.activeGeneration(functionName);
        return active == null || active.equals(admittedGeneration);
    }

    void submitted(InvocationTask task) {
        bestEffort(() -> observer.submitted(task));
    }

    void retried(InvocationTask task) {
        bestEffort(() -> observer.retried(task));
    }

    void completed(InvocationTask task, DispatchResult result, long queueWaitNanos, long serviceNanos) {
        bestEffort(() -> observer.completed(task, result, queueWaitNanos, serviceNanos));
    }

    void terminal(InvocationTask task, InvocationResult result, long endToEndNanos) {
        bestEffort(() -> observer.terminal(task, result, endToEndNanos));
    }

    private static void bestEffort(Runnable observation) {
        try { observation.run(); }
        catch (RuntimeException failure) { log.warn("Attempt observer failed", failure); }
    }
}
