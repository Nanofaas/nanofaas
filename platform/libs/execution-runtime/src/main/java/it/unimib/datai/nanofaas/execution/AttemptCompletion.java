package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.offload.PlannedInvocationRoute;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.RetryScheduler;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a local attempt's result does to its execution: it becomes the invocation's final answer,
 * or the next attempt, prepared under the record monitor and published outside it. Every local
 * result arrives here, whichever path delivered it (transport, callback, attempt timeout).
 */
final class AttemptCompletion {
    private static final Logger log = LoggerFactory.getLogger(AttemptCompletion.class);

    private final ExecutionStore executionStore;
    private final RetryScheduler retry;
    private final RetryBackoff backoff;
    private final AttemptNotifications notifications;

    AttemptCompletion(ExecutionStore executionStore, RetryScheduler retry, RetryBackoff backoff,
                      AttemptNotifications notifications) {
        this.executionStore = executionStore;
        this.retry = retry;
        this.backoff = backoff;
        this.notifications = notifications;
    }

    void complete(String executionId, DispatchResult dispatchResult, Integer completedAttempt) {
        ExecutionRecord executionRecord = executionStore.getOrNull(executionId);
        if (executionRecord == null) {
            return;
        }
        completeExecution(executionRecord, dispatchResult, completedAttempt);
    }

    // S2445: the record IS the per-execution lock object; a dedicated monitor would serialize all executions.
    @SuppressWarnings("java:S2445")
    private void completeExecution(ExecutionRecord executionRecord, DispatchResult dispatchResult, Integer completedAttempt) {
        Conclusion conclusion;
        synchronized (executionRecord) {
            conclusion = completeUnderLock(executionRecord, dispatchResult, completedAttempt);
        }
        // The next attempt is PREPARED under the monitor above and PUBLISHED here, after it was
        // released: publishing walks into the scheduler's own gate, and a queue that had to take
        // the record monitor to accept work would close a lock cycle (ADR 0002). Nothing else
        // can dispatch this attempt meanwhile — it is not in any queue until this line runs.
        if (conclusion.retry() != null) {
            PendingRetry pending = conclusion.retry();
            if (notifications.isCurrentGeneration(pending.functionName(), pending.generation())) {
                notifications.retried(pending.task());
            }
            FinalCompletion completion = publishRetry(executionRecord, pending);
            publishFinalCompletion(executionRecord, completion);
            if (completion == null) executionStore.settle(executionRecord);
        } else {
            FinalCompletion completion = conclusion.completion();
            publishFinalCompletion(executionRecord, completion);
            // Retries remain live. A late callback on a terminal record still drives its
            // canonical settlement, but never replaces the selected answer.
            if (completion == null) executionStore.settle(executionRecord);
        }
    }

    /**
     * State transitions only; final-completion notification and future completion happen
     * outside the record monitor (see publishFinalCompletion) so synchronous whenComplete
     * callbacks never run while the lock is held. Retry publication is likewise entirely
     * outside this method (see completeExecution above) — this method only prepares it.
     */
    private Conclusion completeUnderLock(ExecutionRecord executionRecord,
                                         DispatchResult dispatchResult,
                                         Integer completedAttempt) {
        InvocationResult result = dispatchResult.result();
        InvocationTask currentTask = executionRecord.task();
        if (completedAttempt != null && currentTask.attempt() != completedAttempt) {
            return Conclusion.NONE;
        }

        String functionName = currentTask.functionName();
        // The generation the attempt was admitted under, not whatever the registry considers
        // active by the time metrics are recorded.
        FunctionGeneration generation = executionRecord.currentGeneration();
        if (executionRecord.isTerminal()) {
            return Conclusion.NONE;
        }

        attributeLocalExecution(executionRecord, dispatchResult);

        boolean shouldRetry = !result.success()
                && currentTask.attempt() <= currentTask.functionSpec().maxRetries();
        if (shouldRetry) {
            // prepareRetry always terminates the retry path: the execution is back in QUEUED
            // with its next attempt ready to publish, and never falls through to final completion.
            Instant due = backoff.notBefore(currentTask.attempt(), executionRecord.now(),
                    dispatchResult.retryNotBefore());
            log.debug("Retry execution {} failedAttempt={} nextAttempt={} notBefore={} hintPresent={}",
                    executionRecord.executionId(), currentTask.attempt(), currentTask.attempt() + 1,
                    due, dispatchResult.retryNotBefore() != null);
            return Conclusion.of(prepareRetry(executionRecord, currentTask, result, generation, due));
        }

        if (result.success()) {
            executionRecord.markSuccess(result.output(), result.statusCode(),
                    result.headers(), result.encoding());
        } else {
            executionRecord.markError(result.error(), result.statusCode(), result.headers());
        }
        if (dispatchResult.coldStart()) {
            executionRecord.markColdStart(dispatchResult.initDurationMs() != null ? dispatchResult.initDurationMs() : 0);
        }

        // Durations are monotonic, not wall-clock diffs: an NTP step between admission and
        // completion must not fabricate a latency sample. Service time is this attempt's
        // dispatch-to-completion, wait is this attempt's enqueue-to-dispatch, and the total is
        // the ORIGINAL admission to completion — retries and their waits included.
        Long finishedAtNanos = executionRecord.finishedAtNanos();
        Long startedAtNanos = executionRecord.startedAtNanos();
        Long latencyMs = (startedAtNanos != null && finishedAtNanos != null)
                ? nanosToMs(startedAtNanos, finishedAtNanos)
                : null;
        Long queueWaitMs = startedAtNanos == null
                ? null
                : nanosToMs(executionRecord.attemptEnqueuedAtNanos(), startedAtNanos);
        return Conclusion.of(new FinalCompletion(functionName, result, latencyMs, queueWaitMs,
                dispatchResult.coldStart(), dispatchResult.initDurationMs(), false, generation));
    }

    /**
     * Attributes the execution to the planned local node when this attempt ran there. A managed
     * physical proxy proves handler execution independently of HTTP/result success; without that
     * proof, a success the local transport carried back is proof enough. Stale attempts and
     * terminal records were rejected before this point; refusals and transport errors carry no proof.
     */
    private static void attributeLocalExecution(ExecutionRecord executionRecord, DispatchResult dispatchResult) {
        PlannedInvocationRoute route = executionRecord.plannedRoute();
        if (route == null
                || (route.kind() != PlannedInvocationRoute.Kind.LOCAL
                    && route.kind() != PlannedInvocationRoute.Kind.LOCAL_THEN_CLOUD)) {
            return;
        }
        boolean localSuccess = dispatchResult.result().success()
                && (route.kind() == PlannedInvocationRoute.Kind.LOCAL || executionRecord.capacityOwnedByTransport());
        if (dispatchResult.handlerExecuted() || localSuccess) {
            executionRecord.attributeExecutionNode(route.executionNode());
        }
    }

    static Long nanosToMs(long startNanos, long endNanos) {
        return TimeUnit.NANOSECONDS.toMillis(endNanos - startNanos);
    }

    /**
     * Builds the next attempt and puts the record back in QUEUED, under the record monitor.
     * Nothing is published here: the returned {@link PendingRetry} is handed back to
     * {@link #completeExecution} once the caller has released the monitor.
     */
    private PendingRetry prepareRetry(ExecutionRecord executionRecord, InvocationTask currentTask,
                                      InvocationResult result, FunctionGeneration generation, Instant notBefore) {
        String functionName = currentTask.functionName();
        InvocationTask retryTask = new InvocationTask(
                executionRecord.executionId(),
                functionName,
                currentTask.functionSpec(),
                currentTask.request(),
                null,  // No idempotency key for retry - retry is internal
                currentTask.traceId(),
                // The record's own clock, not Instant.now(): under a steered clock a retry task
                // stamped from the system clock is the one inconsistent timestamp in the record.
                executionRecord.now(),
                currentTask.attempt() + 1,
                currentTask.kind()
        );
        executionRecord.resetForRetry(retryTask);
        // The queue-entry input owner is taken here, with the reset, so the attempt that is about
        // to be published already holds it when it becomes visible to a scheduler.
        return new PendingRetry(executionRecord.prepareForQueue(), retryTask.attempt(),
                functionName, result, generation, notBefore);
    }

    /**
     * Publishes the prepared attempt OUTSIDE the record monitor. On failure the same execution
     * concludes here, after re-validating that it is still parked on this attempt: an
     * administrative expiry or another terminal path that won the record while the publication
     * was failing owns the conclusion instead.
     *
     * @return a final completion when the attempt could not be published, null when it is queued
     */
    // S2445: the record IS the per-execution lock object; a dedicated monitor would serialize all executions.
    @SuppressWarnings("java:S2445")
    private FinalCompletion publishRetry(ExecutionRecord executionRecord, PendingRetry pending) {
        InvocationTask task = pending.task();
        boolean enqueued;
        try {
            enqueued = retry.enqueue(task, pending.notBefore(), () -> {
                task.releaseQueuedInput();
                publishFinalCompletion(executionRecord, concludeExhaustedRetry(executionRecord, pending));
            });
        } catch (RuntimeException | Error ex) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
            task.releaseQueuedInput();
            log.warn("Retry scheduling failed for execution {}, completing with error: {}",
                    executionRecord.executionId(), ex.toString());
            return concludeExhaustedRetry(executionRecord, pending);
        }
        if (enqueued) {
            return null;
        }
        // Expected refusal (e.g. queue full); anything thrown above is belt-and-braces. Both get
        // the same terminal treatment; only the log line differs.
        task.releaseQueuedInput();
        log.warn("Retry queue full for execution {}, completing with error", executionRecord.executionId());
        return concludeExhaustedRetry(executionRecord, pending);
    }

    private FinalCompletion concludeExhaustedRetry(ExecutionRecord executionRecord, PendingRetry pending) {
        synchronized (executionRecord) { // NOSONAR (java:S2445): this object is its own monitor by design; every path locks the same instance
            if (executionRecord.task().attempt() != pending.attempt()
                    || executionRecord.isTerminal()) {
                return null;
            }
            return retryExhaustedUnderLock(executionRecord, pending.functionName(), pending.result(), pending.generation());
        }
    }

    /**
     * The retry could not be scheduled: the invocation concludes in error here, with the SAME
     * error the attempt that triggered the retry already had (there is no new failure to
     * report — the attempt itself failed; only republishing it failed). The attempt never
     * dispatched again, so there is no service time and no wait for it — but the invocation's
     * total, admission to this conclusion, is still real and is what the terminal e2e sample
     * records.
     */
    private static FinalCompletion retryExhaustedUnderLock(ExecutionRecord executionRecord,
                                                           String functionName,
                                                           InvocationResult result,
                                                           FunctionGeneration generation) {
        executionRecord.markError(result.error(), result.statusCode(), result.headers());
        return FinalCompletion.retryExhausted(functionName, result, generation);
    }

    private void publishFinalCompletion(ExecutionRecord executionRecord, FinalCompletion completion) {
        if (completion == null) {
            return;
        }
        String functionName = completion.functionName();
        executionStore.settle(executionRecord);
        // A completion whose function was removed and re-registered since admission (I7) must
        // not attribute its sample to the new generation's meters.
        if (!notifications.isCurrentGeneration(functionName, completion.generation())) {
            return;
        }
        InvocationTask task = executionRecord.task();
        // Cold/warm and the per-attempt timers are a property of a dispatch that actually ran. A
        // retry that never scheduled (queue full) dispatched nothing, so it reports NO_ATTEMPT —
        // success/error still needs recording (it always did, even for this case), but nothing
        // attempt-level does.
        if (completion.retryExhausted()) {
            DispatchResult syntheticResult = DispatchResult.warm(completion.result());
            notifications.completed(task, syntheticResult, AttemptObserver.NO_ATTEMPT, AttemptObserver.NO_ATTEMPT);
            return;
        }
        long queueWaitNanos = completion.queueWaitMs() != null && completion.queueWaitMs() >= 0
                ? TimeUnit.MILLISECONDS.toNanos(completion.queueWaitMs())
                : AttemptObserver.NOT_MEASURABLE;
        long serviceNanos = completion.latencyMs() != null
                ? TimeUnit.MILLISECONDS.toNanos(completion.latencyMs())
                : AttemptObserver.NOT_MEASURABLE;
        DispatchResult attemptResult = new DispatchResult(completion.result(), completion.coldStart(),
                completion.initDurationMs());
        notifications.completed(task, attemptResult, queueWaitNanos, serviceNanos);
    }

    /**
     * What one completion decided under the record monitor: either the invocation's final
     * completion, or a next attempt still to be published, never both.
     */
    private record Conclusion(FinalCompletion completion, PendingRetry retry) {
        private static final Conclusion NONE = new Conclusion(null, null);

        static Conclusion of(FinalCompletion completion) {
            return new Conclusion(completion, null);
        }

        static Conclusion of(PendingRetry retry) {
            return new Conclusion(null, retry);
        }
    }

    /** A next attempt prepared under the record monitor, waiting to be published outside it. */
    private record PendingRetry(InvocationTask task,
                                int attempt,
                                String functionName,
                                InvocationResult result,
                                FunctionGeneration generation,
                                Instant notBefore) {
    }

    private record FinalCompletion(String functionName,
                                   InvocationResult result,
                                   Long latencyMs,
                                   Long queueWaitMs,
                                   boolean coldStart,
                                   Long initDurationMs,
                                   boolean retryExhausted,
                                   FunctionGeneration generation) {
        static FinalCompletion retryExhausted(String functionName, InvocationResult result,
                                              FunctionGeneration generation) {
            return new FinalCompletion(functionName, result, null, null, false, null, true, generation);
        }
    }
}
