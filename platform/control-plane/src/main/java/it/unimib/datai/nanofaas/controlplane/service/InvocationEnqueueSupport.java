package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.queue.QueueFullException;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

final class InvocationEnqueueSupport {

    private InvocationEnqueueSupport() {
    }

    static void enqueueOrThrow(java.util.function.Predicate<InvocationTask> enqueue, Metrics metrics, ExecutionRecord executionRecord) {
        publishOrThrow(enqueue, metrics, executionRecord.prepareForQueue(), true);
    }

    /**
     * Publishes an already prepared task (see {@link ExecutionRecord#prepareForQueue()}) to a
     * queue. Separate from {@link #enqueueOrThrow} so a caller can prepare the task under the
     * execution record's monitor and publish after releasing it.
     *
     * @param countAdmission whether this publication is a user-facing admission. The retry path
     *                       passes {@code false}: the execution was admitted once, when the
     *                       caller invoked it, and republishing its next attempt must not count
     *                       a second admission or a second refusal. The queue-level counters
     *                       ({@code enqueue}, {@code queueRejected}) are recorded either way.
     */
    static void publishOrThrow(java.util.function.Predicate<InvocationTask> enqueue, Metrics metrics,
                               InvocationTask task, boolean countAdmission) {
        boolean enqueued;
        try {
            enqueued = enqueue.test(task);
        } catch (RuntimeException | Error failure) {
            task.releaseQueuedInput();
            throw failure;
        }
        if (!enqueued) {
            task.releaseQueuedInput();
            metrics.queueRejected(task.functionName());
            if (countAdmission) {
                metrics.refused(task.functionName(), task.kind());
            }
            throw new QueueFullException();
        }
        metrics.enqueue(task.functionName());
        if (countAdmission) {
            metrics.admitted(task.functionName(), task.kind());
        }
    }

    /**
     * Runs the admission flow for a freshly created execution: enqueue/dispatch, then
     * publish the idempotency claim; on failure abandon the claim and rethrow.
     * No-op when the lookup is a replay of an existing execution.
     */
    static void admitIfNew(InvocationExecutionFactory.ExecutionLookup lookup,
                           Runnable admissionAction) {
        if (!lookup.isNew()) {
            return;
        }
        try {
            admissionAction.run();
            lookup.publishAdmission();
        } catch (RuntimeException | Error ex) {
            lookup.abandonAdmission();
            throw ex;
        }
    }
}
