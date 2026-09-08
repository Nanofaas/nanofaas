package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.common.model.ErrorInfo;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadFailedException;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadGateway;
import it.unimib.datai.nanofaas.controlplane.queue.QueueFullException;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Handles dispatch to execution runtimes and post-dispatch completion (retry, metrics, state transitions).
 *
 * <p>This is a collaborator of {@link InvocationService}: InvocationService owns the entry-point
 * API (invokeSyncReactive / invokeAsync / getStatus) while this class owns the dispatch and completion
 * lifecycle.</p>
 */
@Service
public class ExecutionCompletionHandler {
    private static final Logger log = LoggerFactory.getLogger(ExecutionCompletionHandler.class);

    /**
     * Marks an outcome the administrative-expiry path fabricated because the real
     * one never arrived, as opposed to a genuine runtime error.
     */
    static final String EXECUTION_EXPIRED_CODE = "EXECUTION_EXPIRED";

    private final ExecutionStore executionStore;
    private final InvocationEnqueuer enqueuer;
    private final DispatcherRouter dispatcherRouter;
    private final Metrics metrics;
    @Nullable private final DeploymentWakeUpGate wakeUpGate;

    /**
     * Production constructor: deployment invocations wait for a scaled-to-zero
     * managed deployment before their external dispatch.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ExecutionCompletionHandler(ExecutionStore executionStore,
                                      @Nullable InvocationEnqueuer enqueuer,
                                      DispatcherRouter dispatcherRouter,
                                      Metrics metrics,
                                      DeploymentWakeUpGate wakeUpGate) {
        this.executionStore = executionStore;
        this.enqueuer = enqueuer == null ? InvocationEnqueuer.noOp() : enqueuer;
        this.dispatcherRouter = dispatcherRouter;
        this.metrics = metrics;
        this.wakeUpGate = wakeUpGate;
        // The store knows nothing about dispatch slots or shared futures; it only
        // knows a record fell out of inFlight on its own. Closing what that record
        // was actually holding is this class's job.
        this.executionStore.onAdministrativeExpiry(this::handleAdministrativeExpiry);
        // Archiving is the ONLY event common to every terminal policy: normal completion, sync
        // timeout, administrative expiry, offload conclusion, and the queue-side terminations a
        // module owns (queue-wait timeout, function removed while queued) — which this class
        // never sees at all. Hanging the end-to-end conclusion here is what makes "exactly one
        // per invocation" true for all of them instead of only for the paths that pass through
        // publishFinalCompletion; the record's own guard keeps it to one.
        this.executionStore.onTerminal(this::recordTerminalConclusionOnce);
    }

    /**
     * Compatibility constructor for direct unit-test construction. Production
     * uses the Spring constructor above.
     */
    public ExecutionCompletionHandler(ExecutionStore executionStore,
                                      @Nullable InvocationEnqueuer enqueuer,
                                      DispatcherRouter dispatcherRouter,
                                      Metrics metrics) {
        this(executionStore, enqueuer, dispatcherRouter, metrics,
                null);
    }

    /**
     * Completion path for offloaded executions: no retry and no dispatch-slot
     * release (offloaded calls never acquired one), just state + metrics + future.
     */
    public void completeOffloadedExecution(String executionId, InvocationResult result) {
        ExecutionRecord executionRecord = executionStore.getOrNull(executionId);
        if (executionRecord == null) {
            return;
        }
        synchronized (executionRecord) {
            if (isTerminal(executionRecord.state())) {
                return;
            }
            if (result.success()) {
                executionRecord.markSuccess(result.output(), result.statusCode(),
                        result.headers(), result.encoding());
            } else {
                executionRecord.markError(result.error());
            }
        }
        String functionName = executionRecord.task().functionName();
        if (result.success()) {
            metrics.success(functionName);
        } else {
            metrics.error(functionName);
        }
        // Future published outside the record monitor (same invariant as
        // publishFinalCompletion): synchronous waiters must not run under the lock.
        executionRecord.completion().complete(result);
        executionStore.settle(executionRecord);
    }

    /**
     * Infrastructure failure of an offloaded call: final by design (no local
     * fallback). The record stores the error; the shared future completes
     * exceptionally so every idempotent waiter surfaces the same 502/504.
     */
    public void failOffloadedExecution(String executionId, OffloadFailedException failure) {
        ExecutionRecord executionRecord = executionStore.getOrNull(executionId);
        if (executionRecord == null) {
            return;
        }
        ErrorInfo error = new ErrorInfo(
                failure.gatewayTimeout() ? OffloadGateway.OFFLOAD_TIMEOUT_CODE : OffloadGateway.OFFLOAD_FAILED_CODE,
                failure.getMessage());
        synchronized (executionRecord) {
            if (isTerminal(executionRecord.state())) {
                return;
            }
            executionRecord.markError(error);
        }
        metrics.error(executionRecord.task().functionName());
        executionRecord.completion().completeExceptionally(failure);
        executionStore.settle(executionRecord);
    }

    public void dispatch(InvocationTask task) {
        ExecutionRecord executionRecord = executionStore.getOrNull(task.executionId());
        if (executionRecord == null) {
            releaseDispatchSlot(task.functionName());
            return;
        }

        boolean terminal;
        synchronized (executionRecord) {
            terminal = executionRecord.isTerminal();
            if (!terminal) {
                executionRecord.markRunning();
                executionRecord.markDispatchedAt();
            }
        }
        if (terminal) {
            releaseDispatchSlotOnce(executionRecord, task.attempt(), task.functionName());
            return;
        }
        metrics.dispatch(task.functionName());

        ExecutionMode mode = task.functionSpec().executionMode();
        int attemptAtDispatch = task.attempt();
        java.util.concurrent.CompletableFuture<DispatchResult> future;
        try {
            future = switch (mode) {
                case LOCAL -> dispatcherRouter.dispatchLocal(task);
                case EXTERNAL -> dispatcherRouter.dispatchExternal(task);
                case DEPLOYMENT -> dispatchDeployment(task);
            };
        } catch (Exception ex) {
            completeExecution(task.executionId(),
                    DispatchResult.warm(InvocationResult.error(mode.name() + "_ERROR", ex.getMessage())),
                    attemptAtDispatch);
            return;
        }

        future.whenComplete((dispatchResult, error) -> {
            if (error != null) {
                Throwable failure = deploymentWakeUpFailure(error);
                completeExecution(task.executionId(),
                        DispatchResult.warm(InvocationResult.error(
                                failure != null ? "DEPLOYMENT_WAKE_UP_FAILED" : mode.name() + "_ERROR",
                                (failure != null ? failure : error).getMessage())),
                        attemptAtDispatch);
            } else {
                completeExecution(task.executionId(), dispatchResult, attemptAtDispatch);
            }
        });
    }

    private CompletableFuture<DispatchResult> dispatchDeployment(InvocationTask task) {
        try {
            if (wakeUpGate == null) {
                return dispatcherRouter.dispatchExternal(task);
            }
            return wakeUpGate.ensureReady(task)
                    .handle((ignored, error) -> {
                        if (error != null) {
                            throw new DeploymentWakeUpException(error);
                        }
                        return ignored;
                    })
                    .thenCompose(ignored -> dispatcherRouter.dispatchExternal(task));
        } catch (Exception error) {
            return CompletableFuture.failedFuture(new DeploymentWakeUpException(error));
        }
    }

    private static Throwable deploymentWakeUpFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof DeploymentWakeUpException) {
                return current.getCause() != null ? current.getCause() : current;
            }
        }
        return null;
    }

    private static final class DeploymentWakeUpException extends RuntimeException {
        private DeploymentWakeUpException(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }

    public void completeExecution(String executionId, DispatchResult dispatchResult) {
        ExecutionRecord executionRecord = executionStore.getOrNull(executionId);
        if (executionRecord == null) {
            return;
        }

        completeExecution(executionRecord, dispatchResult, null);
    }

    public void completeExecution(String executionId, DispatchResult dispatchResult, Integer completedAttempt) {
        ExecutionRecord executionRecord = executionStore.getOrNull(executionId);
        if (executionRecord == null) {
            return;
        }

        completeExecution(executionRecord, dispatchResult, completedAttempt);
    }

    // S2445: the record IS the per-execution lock object; a dedicated monitor would serialize all executions.
    @SuppressWarnings("java:S2445")
    private void completeExecution(ExecutionRecord executionRecord, DispatchResult dispatchResult, Integer completedAttempt) {
        FinalCompletion completion;
        boolean lateResultForTerminalRecord;
        synchronized (executionRecord) {
            completion = completeUnderLock(executionRecord, dispatchResult, completedAttempt);
            // The record was already terminal and this result concerns the current
            // attempt anyway: there is no FinalCompletion to publish, but the shared
            // future is still pending and someone may be parked on it. The retry branch
            // does not reach here - resetForRetry puts the record back to QUEUED - and
            // neither does a result that arrived for an older attempt.
            lateResultForTerminalRecord = completion == null
                    && executionRecord.isTerminal()
                    && (completedAttempt == null || executionRecord.task().attempt() == completedAttempt);
        }
        publishFinalCompletion(executionRecord, completion);
        if (lateResultForTerminalRecord) {
            // A sync timeout made the record terminal while the dispatch was still in
            // flight. The recorded state stays TIMEOUT - a deliberate invariant, already
            // covered by a test - but whoever is still waiting on the shared future,
            // typically a second caller with the same idempotency key and a budget of its
            // own, must get the real answer instead of waiting in vain until its own
            // timeout. complete() on an already-completed future does nothing, so it
            // cannot overwrite anything.
            //
            // The end-to-end conclusion of this timeout is recorded by the store's terminal
            // listener at the settle below: the total (admission -> timeout) is real and
            // measurable, the service time is not (the dispatch was still in flight), so
            // only the total is recorded, never a censored service sample.
            executionRecord.completion().complete(dispatchResult.result());
        }
        // Last, and outside the monitor: completeUnderLock releases the dispatch slot
        // even when it finds the record already terminal (a sync timeout that marked the
        // record while the dispatch was still in flight), and archiving it first would
        // make it unreachable at exactly that step - the slot would stay taken forever.
        // settle() ignores non-terminal records, so the retry branch is left intact.
        executionStore.settle(executionRecord);
    }

    /**
     * State transitions only; final-completion meter recording and future completion
     * happen outside the record monitor (see publishFinalCompletion) so synchronous
     * whenComplete callbacks never run while the lock is held. (Retry-path counters
     * still increment under the lock.)
     */
    private FinalCompletion completeUnderLock(ExecutionRecord executionRecord,
                                              DispatchResult dispatchResult,
                                              Integer completedAttempt) {
        InvocationResult result = dispatchResult.result();
        InvocationTask currentTask = executionRecord.task();
        int attempt = completedAttempt != null ? completedAttempt : currentTask.attempt();
        if (completedAttempt != null && currentTask.attempt() != completedAttempt) {
            return null;
        }

        String functionName = currentTask.functionName();
        releaseDispatchSlotOnce(executionRecord, attempt, functionName);
        if (isTerminal(executionRecord.state())) {
            return null;
        }

        boolean shouldRetry = !result.success()
                && currentTask.attempt() <= currentTask.functionSpec().maxRetries();
        if (shouldRetry) {
            // handleRetry always terminates the retry path: null when the retry was
            // enqueued (never fall through to final completion), a FinalCompletion
            // when retry is exhausted (queue full).
            return handleRetry(executionRecord, currentTask, result);
        }

        if (result.success()) {
            executionRecord.markSuccess(result.output(), result.statusCode(),
                    result.headers(), result.encoding());
        } else {
            executionRecord.markError(result.error());
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
        Long e2eMs = finishedAtNanos == null
                ? null
                : nanosToMs(executionRecord.admittedAtNanos(), finishedAtNanos);
        return new FinalCompletion(functionName, result, latencyMs, queueWaitMs, e2eMs,
                dispatchResult.coldStart(), dispatchResult.initDurationMs(), false);
    }

    private static Long nanosToMs(long startNanos, long endNanos) {
        return TimeUnit.NANOSECONDS.toMillis(endNanos - startNanos);
    }

    /**
     * Returns a final completion when the retry path terminates (queue full), null when the
     * retry was enqueued or when no retry applies (success or attempts exhausted).
     */
    private FinalCompletion handleRetry(ExecutionRecord executionRecord, InvocationTask currentTask, InvocationResult result) {
        if (result.success() || currentTask.attempt() > currentTask.functionSpec().maxRetries()) {
            return null;
        }
        String functionName = currentTask.functionName();
        metrics.retry(functionName);
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
        try {
            InvocationEnqueueSupport.enqueueOrThrow(enqueuer, metrics, executionRecord);
            return null;
        } catch (RuntimeException ex) {
            // QueueFullException is the expected refusal; anything else is belt-and-braces.
            // enqueueOrThrow only throws QueueFullException on its own account, but the enqueuer
            // it wraps is pluggable (queue-backed, sync-queue, executor-backed, or a future
            // implementation) and scheduling a retry is exactly the kind of call whose failure
            // must never leave the record parked in QUEUED with nothing left to complete it.
            // Both get the same terminal treatment; only the log line differs.
            if (ex instanceof QueueFullException) {
                log.warn("Retry queue full for execution {}, completing with error", executionRecord.executionId());
            } else {
                log.warn("Retry scheduling failed for execution {}, completing with error: {}",
                        executionRecord.executionId(), ex.toString());
            }
            return retryExhaustedUnderLock(executionRecord, functionName, result);
        }
    }

    /**
     * The retry could not be scheduled: the invocation concludes in error here. The attempt
     * never dispatched, so there is no service time and no wait for it — but the invocation's
     * total, admission to this conclusion, is still real and is what the e2e timer records.
     */
    private static FinalCompletion retryExhaustedUnderLock(ExecutionRecord executionRecord,
                                                           String functionName,
                                                           InvocationResult result) {
        executionRecord.markError(result.error());
        Long e2eMs = nanosToMs(executionRecord.admittedAtNanos(), executionRecord.finishedAtNanos());
        return FinalCompletion.retryExhausted(functionName, result, e2eMs);
    }

    private void publishFinalCompletion(ExecutionRecord executionRecord, FinalCompletion completion) {
        if (completion == null) {
            return;
        }
        String functionName = completion.functionName();
        // The guard is consumed BEFORE recording, not after: an administrative expiry running on
        // Caffeine's removal executor can win it between completeUnderLock releasing the record
        // monitor and this line. Claiming it first is what makes the end-to-end conclusion single;
        // recording first and claiming after left a window for two samples.
        boolean wonTheConclusion = executionRecord.markMetricsRecorded();
        recordCompletionMetrics(completion, wonTheConclusion);
        if (completion.result().success()) {
            metrics.success(functionName);
        } else {
            metrics.error(functionName);
        }
        executionRecord.completion().complete(completion.result());
    }

    private void recordCompletionMetrics(FinalCompletion completion, boolean wonTheConclusion) {
        String functionName = completion.functionName();
        Metrics.FunctionTimers timers = metrics.timers(functionName);
        // Cold/warm is a property of a dispatch that actually ran. A retry that never scheduled
        // (queue full) dispatched nothing, so it is neither a cold nor a warm start.
        if (!completion.retryExhausted()) {
            if (completion.coldStart()) {
                metrics.coldStart(functionName);
                if (completion.initDurationMs() != null) {
                    timers.initDuration().record(completion.initDurationMs(), TimeUnit.MILLISECONDS);
                }
            } else {
                metrics.warmStart(functionName);
            }
        }
        if (completion.latencyMs() != null) {
            timers.latency().record(completion.latencyMs(), TimeUnit.MILLISECONDS);
        }
        if (completion.queueWaitMs() != null && completion.queueWaitMs() >= 0) {
            timers.queueWait().record(completion.queueWaitMs(), TimeUnit.MILLISECONDS);
        }
        if (wonTheConclusion && completion.e2eMs() != null && completion.e2eMs() >= 0) {
            timers.e2eLatency().record(completion.e2eMs(), TimeUnit.MILLISECONDS);
        }
    }

    private record FinalCompletion(String functionName,
                                   InvocationResult result,
                                   Long latencyMs,
                                   Long queueWaitMs,
                                   Long e2eMs,
                                   boolean coldStart,
                                   Long initDurationMs,
                                   boolean retryExhausted) {
        static FinalCompletion retryExhausted(String functionName, InvocationResult result, Long e2eMs) {
            return new FinalCompletion(functionName, result, null, null, e2eMs, false, null, true);
        }
    }

    /**
     * Overload for backward compatibility (e.g., callback completions without cold start info).
     */
    public void completeExecution(String executionId, InvocationResult result) {
        completeExecution(executionId, DispatchResult.warm(result));
    }

    public void completeExecution(String executionId, InvocationResult result, Integer completedAttempt) {
        completeExecution(executionId, DispatchResult.warm(result), completedAttempt);
    }

    /**
     * The store's only escape hatch for a record nobody ever settled: it fell out
     * of {@code inFlight} because {@code maxLifetime} elapsed, not because a
     * dispatch outcome (or a sync timeout) ever touched it. Three things a lost
     * callback would otherwise never do: conclude whoever is still parked on the
     * shared future, give back the dispatch slot the (possibly still-running,
     * possibly long-dead) local attempt is holding, and archive an outcome so
     * {@code GET /v1/executions/{id}} has something to say instead of 404ing
     * forever.
     *
     * <p>Never overwrites a real result: if the record is already terminal
     * (e.g. a sync caller's own timeout already marked it, while the real
     * dispatch outcome still hasn't arrived) its recorded state/output/error is
     * reused verbatim - this only fabricates an error when nothing else ever
     * will. {@link CompletableFuture#complete} is a no-op on an already-done
     * future, so a real completion racing this one always wins, whichever runs
     * first.
     *
     * <p>Slots are a local-dispatch bookkeeping device, not proof the remote
     * runtime stopped executing: releasing one here only means this control
     * plane stops counting the attempt against its own concurrency budget, the
     * same limit an ordinary completion releases. Whether the invoked process is
     * still running past this point is outside what a slot - or this method -
     * can promise.
     *
     * <p>Only released when {@code dispatchedAt} is set - i.e. {@link #dispatch}
     * actually ran for the current attempt. A record still sitting in a queue
     * (task expired before ever being picked up) or served through offload
     * (which never calls {@link #dispatch}) never acquired a slot in the first
     * place, so there is nothing here to give back.
     */
    private void handleAdministrativeExpiry(ExecutionRecord executionRecord) {
        InvocationResult result;
        boolean wasNonTerminal;
        boolean dispatchedLocally;
        synchronized (executionRecord) {
            dispatchedLocally = executionRecord.snapshot().dispatchedAt() != null;
            wasNonTerminal = !isTerminal(executionRecord.state());
            if (wasNonTerminal) {
                executionRecord.markError(new ErrorInfo(EXECUTION_EXPIRED_CODE,
                        "Execution exceeded its maximum lifetime before a dispatch outcome arrived"));
            }
            result = resultFromRecord(executionRecord);
        }
        InvocationTask task = executionRecord.task();
        if (dispatchedLocally) {
            releaseDispatchSlotOnce(executionRecord, task.attempt(), task.functionName());
        }
        if (wasNonTerminal) {
            metrics.error(task.functionName());
        }
        // An invocation that died for maxLifetime still has one end-to-end conclusion: its total,
        // admission to expiry. The store's terminal listener records it at the settle below,
        // guarded against a late dispatch racing this eviction.
        executionRecord.completion().complete(result);
        executionStore.settle(executionRecord);
    }

    /**
     * The single end-to-end conclusion for an invocation that became terminal outside the normal
     * completion path — a sync caller's timeout, an administrative expiry, or an offloaded call
     * concluded by the remote plane — where the service time was censored (no dispatch on this
     * side produced an observed completion). Only the total duration is recorded, and only once:
     * the record's guard makes a late dispatch callback racing the timeout or the eviction a no-op
     * the second time.
     *
     * <p>The measurability check comes BEFORE the guard is claimed. Claiming first would burn the
     * invocation's one conclusion on a record that has no {@code finishedAtNanos} to record, and
     * the real terminal path arriving afterwards would then find the guard spent and stay silent.
     */
    private void recordTerminalConclusionOnce(ExecutionRecord executionRecord) {
        Long finishedAtNanos = executionRecord.finishedAtNanos();
        if (finishedAtNanos == null) {
            return;
        }
        if (!executionRecord.markMetricsRecorded()) {
            return;
        }
        Long e2eMs = nanosToMs(executionRecord.admittedAtNanos(), finishedAtNanos);
        if (e2eMs >= 0) {
            metrics.timers(executionRecord.task().functionName())
                    .e2eLatency().record(e2eMs, TimeUnit.MILLISECONDS);
        }
    }

    /** Called under the record's monitor: reads snapshot() fields directly. */
    private static InvocationResult resultFromRecord(ExecutionRecord executionRecord) {
        ExecutionRecord.Snapshot snapshot = executionRecord.snapshot();
        if (snapshot.state() == it.unimib.datai.nanofaas.controlplane.execution.ExecutionState.SUCCESS) {
            return InvocationResult.successWithEnvelope(snapshot.output(), snapshot.statusCode(),
                    snapshot.headers(), snapshot.encoding());
        }
        ErrorInfo error = snapshot.lastError() != null
                ? snapshot.lastError()
                : new ErrorInfo(EXECUTION_EXPIRED_CODE,
                        "Execution exceeded its maximum lifetime before a dispatch outcome arrived");
        return new InvocationResult(false, null, error);
    }

    private void releaseDispatchSlot(String functionName) {
        enqueuer.releaseDispatchSlot(functionName);
    }

    private void releaseDispatchSlotOnce(ExecutionRecord executionRecord, int attempt, String functionName) {
        if (executionRecord.markDispatchSlotReleased(attempt)) {
            releaseDispatchSlot(functionName);
        }
    }

    private static boolean isTerminal(it.unimib.datai.nanofaas.controlplane.execution.ExecutionState state) {
        return state == it.unimib.datai.nanofaas.controlplane.execution.ExecutionState.SUCCESS
                || state == it.unimib.datai.nanofaas.controlplane.execution.ExecutionState.ERROR
                || state == it.unimib.datai.nanofaas.controlplane.execution.ExecutionState.TIMEOUT;
    }
}
