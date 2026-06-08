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
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Handles dispatch to execution runtimes and post-dispatch completion (retry, metrics, state transitions).
 *
 * <p>This is a collaborator of {@link InvocationService}: InvocationService owns the entry-point
 * API (invokeSync / invokeAsync / getStatus) while this class owns the dispatch and completion
 * lifecycle.</p>
 */
@Service
public class ExecutionCompletionHandler {
    private static final Logger log = LoggerFactory.getLogger(ExecutionCompletionHandler.class);

    private final ExecutionStore executionStore;
    private final InvocationEnqueuer enqueuer;
    private final DispatcherRouter dispatcherRouter;
    private final Metrics metrics;
    private final Map<ExecutionRecord, Set<Integer>> releasedDispatchAttempts =
            Collections.synchronizedMap(new WeakHashMap<>());

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
        synchronized (record) {
            InvocationResult result = dispatchResult.result();
            InvocationTask currentTask = record.task();
            int attempt = completedAttempt != null ? completedAttempt : currentTask.attempt();
            if (completedAttempt != null && currentTask.attempt() != completedAttempt) {
                return;
            }

            String functionName = currentTask.functionName();
            releaseDispatchSlotOnce(record, attempt, functionName);
            if (isTerminal(record.state())) {
                return;
            }
            Metrics.FunctionTimers timers = metrics.timers(functionName);

            // Check if retry is needed BEFORE completing the future
            boolean shouldRetry = !result.success()
                    && currentTask.attempt() < currentTask.functionSpec().maxRetries();

            if (shouldRetry) {
                // Schedule retry - don't complete the future yet
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
                // Reset record atomically for retry, preserving CompletableFuture
                record.resetForRetry(retryTask);
                try {
                    InvocationEnqueueSupport.enqueueOrThrow(enqueuer, metrics, record);
                } catch (QueueFullException ex) {
                    log.warn("Retry queue full for execution {}, completing with error", record.executionId());
                    record.markError(result.error());
                    metrics.error(record.task().functionName());
                    record.completion().complete(result);
                }
                return;
            }

            // Record cold start info from runtime headers
            if (dispatchResult.coldStart()) {
                record.markColdStart(dispatchResult.initDurationMs() != null ? dispatchResult.initDurationMs() : 0);
                metrics.coldStart(functionName);
                if (dispatchResult.initDurationMs() != null) {
                    timers.initDuration().record(dispatchResult.initDurationMs(), TimeUnit.MILLISECONDS);
                }
            } else {
                metrics.warmStart(functionName);
            }

            // No retry - complete the execution atomically
            ExecutionRecord.Snapshot beforeComplete = record.snapshot();
            InvocationTask task = beforeComplete.task();
            java.time.Instant startedAt = beforeComplete.startedAt();
            if (result.success()) {
                record.markSuccess(result.output());
                metrics.success(functionName);
            } else {
                record.markError(result.error());
                metrics.error(functionName);
            }

            java.time.Instant finishedAt = record.finishedAt();
            if (startedAt != null && finishedAt != null) {
                long durationMs = finishedAt.toEpochMilli() - startedAt.toEpochMilli();
                timers.latency().record(durationMs, TimeUnit.MILLISECONDS);
            }

            // Queue wait time: startedAt - enqueuedAt
            if (task.enqueuedAt() != null && startedAt != null) {
                long queueWaitMs = startedAt.toEpochMilli() - task.enqueuedAt().toEpochMilli();
                if (queueWaitMs >= 0) {
                    timers.queueWait().record(queueWaitMs, TimeUnit.MILLISECONDS);
                }
            }

            // E2E latency: finishedAt - enqueuedAt
            if (task.enqueuedAt() != null && finishedAt != null) {
                long e2eMs = finishedAt.toEpochMilli() - task.enqueuedAt().toEpochMilli();
                if (e2eMs >= 0) {
                    timers.e2eLatency().record(e2eMs, TimeUnit.MILLISECONDS);
                }
            }

            record.completion().complete(result);
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
        if (markDispatchSlotReleased(record, attempt)) {
            releaseDispatchSlot(functionName);
        }
    }

    private boolean markDispatchSlotReleased(ExecutionRecord record, int attempt) {
        synchronized (releasedDispatchAttempts) {
            Set<Integer> attempts = releasedDispatchAttempts.computeIfAbsent(record, ignored -> new HashSet<>());
            return attempts.add(attempt);
        }
    }

    private static boolean isTerminal(it.unimib.datai.nanofaas.controlplane.execution.ExecutionState state) {
        return state == it.unimib.datai.nanofaas.controlplane.execution.ExecutionState.SUCCESS
                || state == it.unimib.datai.nanofaas.controlplane.execution.ExecutionState.ERROR
                || state == it.unimib.datai.nanofaas.controlplane.execution.ExecutionState.TIMEOUT;
    }
}
