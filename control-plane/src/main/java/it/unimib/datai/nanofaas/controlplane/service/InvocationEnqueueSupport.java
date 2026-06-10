package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.queue.QueueFullException;

final class InvocationEnqueueSupport {

    private InvocationEnqueueSupport() {
    }

    static void enqueueOrThrow(InvocationEnqueuer enqueuer, Metrics metrics, ExecutionRecord record) {
        boolean enqueued = enqueuer.enqueue(record.task());
        if (!enqueued) {
            metrics.queueRejected(record.task().functionName());
            throw new QueueFullException();
        }
        metrics.enqueue(record.task().functionName());
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
        } catch (RuntimeException ex) {
            lookup.abandonAdmission();
            throw ex;
        }
    }
}
