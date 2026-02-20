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
        java.util.concurrent.CompletableFuture<DispatchResult> future;
        try {
            future = switch (mode) {
                case LOCAL -> dispatcherRouter.dispatchLocal(task);
                case POOL, DEPLOYMENT -> dispatcherRouter.dispatchPool(task);
            };
        } catch (Exception ex) {
            completeExecution(task.executionId(),
                    DispatchResult.warm(InvocationResult.error(mode.name() + "_ERROR", ex.getMessage())));
            return;
        }

        future.whenComplete((dispatchResult, error) -> {
            if (error != null) {
                completeExecution(task.executionId(),
                        DispatchResult.warm(InvocationResult.error(mode.name() + "_ERROR", error.getMessage())));
            } else {
                completeExecution(task.executionId(), dispatchResult);
            }
        });
    }

    public void completeExecution(String executionId, DispatchResult dispatchResult) {
        InvocationResult result = dispatchResult.result();
        ExecutionRecord record = executionStore.getOrNull(executionId);
        if (record == null) {
            return;
        }

        String functionName = record.task().functionName();
        releaseDispatchSlot(functionName);

        // Check if retry is needed BEFORE completing the future
        boolean shouldRetry = !result.success()
                && record.task().attempt() < record.task().functionSpec().maxRetries();

        if (shouldRetry) {
            // Schedule retry - don't complete the future yet
            metrics.retry(record.task().functionName());
            InvocationTask retryTask = new InvocationTask(
                    record.executionId(),
                    record.task().functionName(),
                    record.task().functionSpec(),
                    record.task().request(),
                    null,  // No idempotency key for retry - retry is internal
                    record.task().traceId(),
                    Instant.now(),
                    record.task().attempt() + 1
            );
            // Reset record atomically for retry, preserving CompletableFuture
            record.resetForRetry(retryTask);
            try {
                enqueueOrThrow(record);
            } catch (QueueFullException ex) {
                log.warn("Retry queue full for execution {}, completing with error", executionId);
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
                metrics.initDuration(functionName).record(dispatchResult.initDurationMs(), TimeUnit.MILLISECONDS);
            }
        } else {
            metrics.warmStart(functionName);
        }

        // No retry - complete the execution atomically
        ExecutionRecord.Snapshot beforeComplete = record.snapshot();
        if (result.success()) {
            record.markSuccess(result.output());
            metrics.success(functionName);
        } else {
            record.markError(result.error());
            metrics.error(functionName);
        }

        ExecutionRecord.Snapshot afterComplete = record.snapshot();
        if (beforeComplete.startedAt() != null && afterComplete.finishedAt() != null) {
            long durationMs = afterComplete.finishedAt().toEpochMilli() - beforeComplete.startedAt().toEpochMilli();
            metrics.latency(functionName).record(durationMs, TimeUnit.MILLISECONDS);
        }

        // Queue wait time: startedAt - enqueuedAt
        InvocationTask task = record.task();
        if (task.enqueuedAt() != null && beforeComplete.startedAt() != null) {
            long queueWaitMs = beforeComplete.startedAt().toEpochMilli() - task.enqueuedAt().toEpochMilli();
            if (queueWaitMs >= 0) {
                metrics.queueWait(functionName).record(queueWaitMs, TimeUnit.MILLISECONDS);
            }
        }

        // E2E latency: finishedAt - enqueuedAt
        if (task.enqueuedAt() != null && afterComplete.finishedAt() != null) {
            long e2eMs = afterComplete.finishedAt().toEpochMilli() - task.enqueuedAt().toEpochMilli();
            if (e2eMs >= 0) {
                metrics.e2eLatency(functionName).record(e2eMs, TimeUnit.MILLISECONDS);
            }
        }

        record.completion().complete(result);
    }

    /**
     * Overload for backward compatibility (e.g., callback completions without cold start info).
     */
    public void completeExecution(String executionId, InvocationResult result) {
        completeExecution(executionId, DispatchResult.warm(result));
    }

    private void releaseDispatchSlot(String functionName) {
        enqueuer.releaseDispatchSlot(functionName);
    }

    private void enqueueOrThrow(ExecutionRecord record) {
        boolean enqueued = enqueuer.enqueue(record.task());
        if (!enqueued) {
            metrics.queueRejected(record.task().functionName());
            throw new QueueFullException();
        }
        metrics.enqueue(record.task().functionName());
    }
}
