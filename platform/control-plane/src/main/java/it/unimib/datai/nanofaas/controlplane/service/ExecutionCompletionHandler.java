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
        synchronized (executionRecord) {
            completion = completeUnderLock(executionRecord, dispatchResult, completedAttempt);
        }
        publishFinalCompletion(executionRecord, completion);
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

        Instant enqueuedAt = currentTask.enqueuedAt();
        Instant startedAt = executionRecord.startedAt();
        if (result.success()) {
            executionRecord.markSuccess(result.output(), result.statusCode(),
                    result.headers(), result.encoding());
        } else {
            executionRecord.markError(result.error());
        }
        Instant finishedAt = executionRecord.finishedAt();
        if (dispatchResult.coldStart()) {
            executionRecord.markColdStart(dispatchResult.initDurationMs() != null ? dispatchResult.initDurationMs() : 0);
        }

        Long latencyMs = elapsedMs(startedAt, finishedAt);
        Long queueWaitMs = elapsedMs(enqueuedAt, startedAt);
        Long e2eMs = elapsedMs(enqueuedAt, finishedAt);
        return new FinalCompletion(functionName, result, latencyMs, queueWaitMs, e2eMs,
                dispatchResult.coldStart(), dispatchResult.initDurationMs(), false);
    }

    private static Long elapsedMs(Instant start, Instant end) {
        return (start != null && end != null) ? end.toEpochMilli() - start.toEpochMilli() : null;
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
                Instant.now(),
                currentTask.attempt() + 1,
                currentTask.kind()
        );
        executionRecord.resetForRetry(retryTask);
        try {
            InvocationEnqueueSupport.enqueueOrThrow(enqueuer, metrics, executionRecord);
            return null;
        } catch (QueueFullException _) {
            log.warn("Retry queue full for execution {}, completing with error", executionRecord.executionId());
            executionRecord.markError(result.error());
            return FinalCompletion.retryExhausted(functionName, result);
        }
    }

    private void publishFinalCompletion(ExecutionRecord executionRecord, FinalCompletion completion) {
        if (completion == null) {
            return;
        }
        String functionName = completion.functionName();
        if (!completion.retryExhausted()) {
            recordCompletionMetrics(completion);
        }
        if (completion.result().success()) {
            metrics.success(functionName);
        } else {
            metrics.error(functionName);
        }
        executionRecord.completion().complete(completion.result());
    }

    private void recordCompletionMetrics(FinalCompletion completion) {
        String functionName = completion.functionName();
        Metrics.FunctionTimers timers = metrics.timers(functionName);
        if (completion.coldStart()) {
            metrics.coldStart(functionName);
            if (completion.initDurationMs() != null) {
                timers.initDuration().record(completion.initDurationMs(), TimeUnit.MILLISECONDS);
            }
        } else {
            metrics.warmStart(functionName);
        }
        if (completion.latencyMs() != null) {
            timers.latency().record(completion.latencyMs(), TimeUnit.MILLISECONDS);
        }
        if (completion.queueWaitMs() != null && completion.queueWaitMs() >= 0) {
            timers.queueWait().record(completion.queueWaitMs(), TimeUnit.MILLISECONDS);
        }
        if (completion.e2eMs() != null && completion.e2eMs() >= 0) {
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
        static FinalCompletion retryExhausted(String functionName, InvocationResult result) {
            return new FinalCompletion(functionName, result, null, null, null, false, null, true);
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
