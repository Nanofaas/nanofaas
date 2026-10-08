package it.unimib.datai.nanofaas.controlplane.scheduler;

import java.util.function.BiConsumer;

/**
 * Queue events delivered to the existing execution owner. Only admitted work
 * lost by a queue is rejected here; refusal of a new admission remains the
 * admitting caller's responsibility. Events are fenced by execution and attempt.
 * Notifications never grant access to the mutable record or its shared future.
 */
public interface QueueLifecycle {
    void expired(InvocationTask task);
    void removed(InvocationTask task);
    void rejected(InvocationTask task, Throwable failure);

    /** Terminal or administratively expired executions, for queue-owned fence cleanup. */
    void onExecutionGone(BiConsumer<String, String> listener);
}
