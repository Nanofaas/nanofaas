package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
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

    public void dispatch(InvocationTask task) {
        ExecutionRecord record = executionStore.getOrNull(task.executionId());
        if (record == null) {
            releaseDispatchSlot(task.functionName());
            return;
        }

        record.markRunning();
        record.markDispatchedAt();
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
     * State transitions only; meter recording and future completion happen outside the
     * record monitor (see publishFinalCompletion) so synchronous whenComplete callbacks
     * never run while the lock is held.
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
                && currentTask.attempt() < currentTask.functionSpec().maxRetries();

        if (shouldRetry) {
            metrics.retry(functionName);
            InvocationTask retryTask = new InvocationTask(
                    record.executionId(),
                    functionName,
                    currentTask.functionSpec(),
                    currentTask.request(),
                    null,  // No idempotency key for retry - retry is internal
                    currentTask.traceId(),
                    Instant.now(),
                    currentTask.attempt() + 1
            );
            record.resetForRetry(retryTask);
            try {
                InvocationEnqueueSupport.enqueueOrThrow(enqueuer, metrics, record);
                return null;
            } catch (QueueFullException ex) {
                log.warn("Retry queue full for execution {}, completing with error", record.executionId());
                record.markError(result.error());
                return FinalCompletion.retryExhausted(functionName, result);
            }
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

        Long latencyMs = (startedAt != null && finishedAt != null)
                ? finishedAt.toEpochMilli() - startedAt.toEpochMilli() : null;
        Long queueWaitMs = (enqueuedAt != null && startedAt != null)
                ? startedAt.toEpochMilli() - enqueuedAt.toEpochMilli() : null;
        Long e2eMs = (enqueuedAt != null && finishedAt != null)
                ? finishedAt.toEpochMilli() - enqueuedAt.toEpochMilli() : null;
        return new FinalCompletion(functionName, result, latencyMs, queueWaitMs, e2eMs,
                dispatchResult.coldStart(), dispatchResult.initDurationMs(), false);
    }

    private void publishFinalCompletion(ExecutionRecord record, FinalCompletion completion) {
        if (completion == null) {
            return;
        }
        String functionName = completion.functionName();
        if (!completion.retryExhausted()) {
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
        if (completion.result().success()) {
            metrics.success(functionName);
        } else {
            metrics.error(functionName);
        }
        record.completion().complete(completion.result());
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
