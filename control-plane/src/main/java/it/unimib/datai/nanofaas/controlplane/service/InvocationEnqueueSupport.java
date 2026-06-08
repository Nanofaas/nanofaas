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
}
