package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchLease;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Retry-scheduling capability for the no-queue-module profile ("core only" - neither
 * async-queue nor sync-queue loaded). Before this class existed, the default
 * {@link InvocationEnqueuer} bean in that profile was {@link InvocationEnqueuer#noOp()},
 * whose {@code enqueue} throws {@link UnsupportedOperationException}; {@link
 * ExecutionCompletionHandler}'s retry path calls {@code enqueue} unconditionally
 * (see {@code handleRetry}), so a failed attempt with retries remaining left the
 * execution parked in {@code QUEUED} forever - nothing was ever going to complete it.
 *
 * <p>{@link #enabled()} intentionally stays {@code false}: it gates two admission
 * sites that this class is not meant to serve - the async {@code :enqueue} HTTP
 * endpoint ({@code InvocationService#invokeAsync}, which must keep answering 501
 * without a real async queue) and the sync admission fallback in {@code
 * ReactiveInvocationCoordinator#admitLocally} (which dispatches the *first* attempt
 * inline, with no queue in front of it). The retry path is the only caller of {@link
 * #enqueue} on this class - both {@code QueueBackedEnqueuer} and {@code
 * SyncQueueInvocationEnqueuer} follow the same split.
 *
 * <p>Submission goes through a bounded {@link ExecutorService}, never a direct/inline
 * call. That matters because a retry whose dispatch resolves synchronously (LOCAL
 * mode, or a fast test double) re-enters {@link #enqueue} from inside the previous
 * attempt's own completion callback; since {@code execute} only appends to the pool's
 * work queue and returns, each retry becomes a new task picked up by a worker's own
 * loop rather than a deeper stack frame, so a long retry chain cannot overflow the
 * stack.
 *
 * <p>Each retry still acquires the function's capacity lease (invariant I4/I5): a retry
 * is an attempt, so the no-queue profile applies the configured concurrency to it too.
 * When there is no room the lease acquisition fails and {@code enqueue} returns
 * {@code false}, which sends the caller down the exact path an exhausted or full queue
 * already takes (see {@code ExecutionCompletionHandler#handleRetry}) - the request still
 * terminates, it never leaks an orphaned {@code QUEUED} record.
 */
final class ExecutorBackedInvocationEnqueuer implements InvocationEnqueuer {
    private static final Logger log = LoggerFactory.getLogger(ExecutorBackedInvocationEnqueuer.class);

    private final BiConsumer<InvocationTask, DispatchLease> dispatchWithLease;
    private final FunctionCapacityRegistry capacityRegistry;
    private final ExecutorService executor;

    ExecutorBackedInvocationEnqueuer(BiConsumer<InvocationTask, DispatchLease> dispatchWithLease,
                                     FunctionCapacityRegistry capacityRegistry,
                                     ExecutorService executor) {
        this.dispatchWithLease = dispatchWithLease;
        this.capacityRegistry = capacityRegistry;
        this.executor = executor;
    }

    /** Test-only convenience: a plain dispatch consumer with no capacity accounting. */
    ExecutorBackedInvocationEnqueuer(Consumer<InvocationTask> dispatch, ExecutorService executor) {
        this((task, lease) -> {
            dispatch.accept(task);
            if (lease != null) {
                lease.release();
            }
        }, new FunctionCapacityRegistry(), executor);
    }

    @Override
    public boolean enqueue(InvocationTask task) {
        DispatchLease lease = capacityRegistry.tryAcquireLease(
                task.functionName(), task.functionSpec().concurrency());
        if (lease == null) {
            log.warn("Retry refused for execution {} (function {}, attempt {}): no capacity",
                    task.executionId(), task.functionName(), task.attempt());
            return false;
        }
        try {
            executor.execute(() -> dispatchWithLease.accept(task, lease));
            return true;
        } catch (RuntimeException ex) {
            lease.release();
            log.warn("Retry scheduling rejected for execution {} (function {}, attempt {}): {}",
                    task.executionId(), task.functionName(), task.attempt(), ex.toString());
            return false;
        }
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public boolean tryAcquireSlot(String functionName) {
        return true;
    }

    @Override
    public void releaseDispatchSlot(String functionName) {
        // no-op: this profile has no queue-side concurrency bookkeeping to release.
    }

    /** Spring destroy-method hook: stop accepting new retries and let in-flight ones finish. */
    void shutdown() {
        executor.shutdown();
    }
}
