package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

/** Initial admission only. HTTP response mode does not select the function's queue strategy. */
public interface InvocationEnqueuer {
    enum QueueStrategy { DIRECT, FUNCTION_QUEUE }

    QueueStrategy queueStrategy();
    boolean supportsAsync();
    boolean enqueue(InvocationTask task);

    /** Advisory early-refusal hint; enqueue remains authoritative. */
    default boolean isQueueFull(String functionName) { return false; }

    static InvocationEnqueuer noOp() { return NoOpInvocationEnqueuer.INSTANCE; }
}
