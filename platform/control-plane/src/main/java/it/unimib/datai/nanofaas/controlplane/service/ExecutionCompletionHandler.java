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

    public ExecutionCompletionHandler(ExecutionStore executionStore,
                                      @Nullable InvocationEnqueuer enqueuer,
                                      DispatcherRouter dispatcherRouter,
                                      Metrics metrics) {
        this.executionStore = executionStore;
        this.enqueuer = enqueuer == null ? InvocationEnqueuer.noOp() : enqueuer;
        this.dispatcherRouter = dispatcherRouter;
        this.metrics = metrics;
    }

    /**
     * Completion path for offloaded executions: no retry and no dispatch-slot
     * release (offloaded calls never acquired one), just state + metrics + future.
     */
    public void completeOffloadedExecution(String executionId, InvocationResult result) {
        ExecutionRecord record = executionStore.getOrNull(executionId);
        if (record == null) {
            return;
        }
        synchronized (record) {
            if (isTerminal(record.state())) {
                return;
            }
            if (result.success()) {
                record.markSuccess(result.output());
            } else {
                record.markError(result.error());
            }
        }
        String functionName = record.task().functionName();
        if (result.success()) {
            metrics.success(functionName);
        } else {
            metrics.error(functionName);
        }
        // Future published outside the record monitor (same invariant as
        // publishFinalCompletion): synchronous waiters must not run under the lock.
        record.completion().complete(result);
    }

    /**
     * Infrastructure failure of an offloaded call: final by design (no local
     * fallback). The record stores the error; the shared future completes
     * exceptionally so every idempotent waiter surfaces the same 502/504.
     */
    public void failOffloadedExecution(String executionId, OffloadFailedException failure) {
        ExecutionRecord record = executionStore.getOrNull(executionId);
        if (record == null) {
            return;
        }
        ErrorInfo error = new ErrorInfo(
                failure.gatewayTimeout() ? OffloadGateway.OFFLOAD_TIMEOUT_CODE : OffloadGateway.OFFLOAD_FAILED_CODE,
                failure.getMessage());
        synchronized (record) {
            if (isTerminal(record.state())) {
                return;
            }
            record.markError(error);
        }
        metrics.error(record.task().functionName());
        record.completion().completeExceptionally(failure);
    }

    public void dispatch(InvocationTask task) {
        ExecutionRecord record = executionStore.getOrNull(task.executionId());
        if (record == null) {
            releaseDispatchSlot(task.functionName());
            return;
        }

        boolean terminal;
        synchronized (record) {
            terminal = record.isTerminal();
            if (!terminal) {
                record.markRunning();
                record.markDispatchedAt();
            }
        }
        if (terminal) {
            releaseDispatchSlotOnce(record, task.attempt(), task.functionName());
            return;
        }
        metrics.dispatch(task.functionName());

        ExecutionMode mode = task.functionSpec().executionMode();
        int attemptAtDispatch = task.attempt();
        java.util.concurrent.CompletableFuture<DispatchResult> future;
        try {
            future = switch (mode) {
                case LOCAL -> dispatcherRouter.dispatchLocal(task);
                case POOL, DEPLOYMENT -> dispatcherRouter.dispatchPool(task);
            };
        } catch (Exception ex) {
            completeExecution(task.executionId(),
                    DispatchResult.warm(InvocationResult.error(mode.name() + "_ERROR", ex.getMessage())),
                    attemptAtDispatch);
            return;
        }

        future.whenComplete((dispatchResult, error) -> {
            if (error != null) {
                completeExecution(task.executionId(),
                        DispatchResult.warm(InvocationResult.error(mode.name() + "_ERROR", error.getMessage())),
                        attemptAtDispatch);
            } else {
                completeExecution(task.executionId(), dispatchResult, attemptAtDispatch);
            }
        });
    }

    public void completeExecution(String executionId, DispatchResult dispatchResult) {
        ExecutionRecord record = executionStore.getOrNull(executionId);
        if (record == null) {
            return;
        }

        completeExecution(record, dispatchResult, null);
    }

    public void completeExecution(String executionId, DispatchResult dispatchResult, Integer completedAttempt) {
        ExecutionRecord record = executionStore.getOrNull(executionId);
        if (record == null) {
            return;
        }

        completeExecution(record, dispatchResult, completedAttempt);
    }

    private void completeExecution(ExecutionRecord record, DispatchResult dispatchResult, Integer completedAttempt) {
        FinalCompletion completion;
        synchronized (record) {
            completion = completeUnderLock(record, dispatchResult, completedAttempt);
        }
        publishFinalCompletion(record, completion);
    }

    /**
     * State transitions only; final-completion meter recording and future completion
     * happen outside the record monitor (see publishFinalCompletion) so synchronous
     * whenComplete callbacks never run while the lock is held. (Retry-path counters
     * still increment under the lock.)
     */
    private FinalCompletion completeUnderLock(ExecutionRecord record,
                                              DispatchResult dispatchResult,
                                              Integer completedAttempt) {
        InvocationResult result = dispatchResult.result();
        InvocationTask currentTask = record.task();
        int attempt = completedAttempt != null ? completedAttempt : currentTask.attempt();
        if (completedAttempt != null && currentTask.attempt() != completedAttempt) {
            return null;
        }

        String functionName = currentTask.functionName();
        releaseDispatchSlotOnce(record, attempt, functionName);
        if (isTerminal(record.state())) {
            return null;
        }

        boolean shouldRetry = !result.success()
                && currentTask.attempt() <= currentTask.functionSpec().maxRetries();
        if (shouldRetry) {
            // handleRetry always terminates the retry path: null when the retry was
            // enqueued (never fall through to final completion), a FinalCompletion
            // when retry is exhausted (queue full).
            return handleRetry(record, currentTask, result);
        }

        Instant enqueuedAt = currentTask.enqueuedAt();
        Instant startedAt = record.startedAt();
        if (result.success()) {
            record.markSuccess(result.output());
        } else {
            record.markError(result.error());
        }
        Instant finishedAt = record.finishedAt();
        if (dispatchResult.coldStart()) {
            record.markColdStart(dispatchResult.initDurationMs() != null ? dispatchResult.initDurationMs() : 0);
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
                currentTask.attempt() + 1
        );
        executionRecord.resetForRetry(retryTask);
        try {
            InvocationEnqueueSupport.enqueueOrThrow(enqueuer, metrics, executionRecord);
            return null;
        } catch (QueueFullException ex) {
            log.warn("Retry queue full for execution {}, completing with error", executionRecord.executionId());
            executionRecord.markError(result.error());
            return FinalCompletion.retryExhausted(functionName, result);
        }
    }

    private void publishFinalCompletion(ExecutionRecord record, FinalCompletion completion) {
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
        record.completion().complete(completion.result());
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

    private void releaseDispatchSlotOnce(ExecutionRecord record, int attempt, String functionName) {
        if (record.markDispatchSlotReleased(attempt)) {
            releaseDispatchSlot(functionName);
        }
    }

    private static boolean isTerminal(it.unimib.datai.nanofaas.controlplane.execution.ExecutionState state) {
        return state == it.unimib.datai.nanofaas.controlplane.execution.ExecutionState.SUCCESS
                || state == it.unimib.datai.nanofaas.controlplane.execution.ExecutionState.ERROR
                || state == it.unimib.datai.nanofaas.controlplane.execution.ExecutionState.TIMEOUT;
    }
}
