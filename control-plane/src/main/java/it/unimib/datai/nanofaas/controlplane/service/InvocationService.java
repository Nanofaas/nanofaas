package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.ExecutionStatus;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.queue.QueueFullException;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionNotFoundException;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class InvocationService {
    private static final Logger log = LoggerFactory.getLogger(InvocationService.class);

    private final FunctionService functionService;
    private final InvocationEnqueuer enqueuer;
    private final ExecutionStore executionStore;
    private final IdempotencyStore idempotencyStore;
    private final DispatcherRouter dispatcherRouter;
    private final RateLimiter rateLimiter;
    private final Metrics metrics;
    private final SyncQueueGateway syncQueueGateway;

    public InvocationService(FunctionService functionService,
                             @Nullable InvocationEnqueuer enqueuer,
                             ExecutionStore executionStore,
                             IdempotencyStore idempotencyStore,
                             DispatcherRouter dispatcherRouter,
                             RateLimiter rateLimiter,
                             Metrics metrics,
                             @Autowired(required = false) @Nullable SyncQueueGateway syncQueueGateway) {
        this.functionService = functionService;
        this.enqueuer = enqueuer == null ? InvocationEnqueuer.noOp() : enqueuer;
        this.executionStore = executionStore;
        this.idempotencyStore = idempotencyStore;
        this.dispatcherRouter = dispatcherRouter;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.syncQueueGateway = syncQueueGateway == null ? SyncQueueGateway.noOp() : syncQueueGateway;
    }

    public InvocationResponse invokeSync(String functionName,
                                         InvocationRequest request,
                                         String idempotencyKey,
                                         String traceId,
                                         Integer timeoutOverrideMs) throws InterruptedException {
        enforceRateLimit();

        FunctionSpec spec = functionService.get(functionName).orElseThrow(FunctionNotFoundException::new);
        ExecutionLookup lookup = createOrReuseExecution(functionName, spec, request, idempotencyKey, traceId);
        ExecutionRecord record = lookup.record();

        if (record.state() == ExecutionState.SUCCESS || record.state() == ExecutionState.ERROR) {
            InvocationResult result = record.lastError() == null
                    ? InvocationResult.success(record.output())
                    : new InvocationResult(false, null, record.lastError());
            return toResponse(record, result);
        }

        if (lookup.isNew()) {
            if (syncQueueEnabled()) {
                syncQueueGateway.enqueueOrThrow(record.task());
            } else if (enqueuer.enabled()) {
                enqueueOrThrow(record);
            } else {
                dispatch(record.task());
            }
        }

        int timeoutMs = timeoutOverrideMs == null ? spec.timeoutMs() : timeoutOverrideMs;
        try {
            InvocationResult result = record.completion().get(timeoutMs, TimeUnit.MILLISECONDS);
            if (result.error() != null && "QUEUE_TIMEOUT".equals(result.error().code())) {
                throw new SyncQueueRejectedException(SyncQueueRejectReason.TIMEOUT, syncQueueRetryAfterSeconds());
            }
            return toResponse(record, result);
        } catch (SyncQueueRejectedException ex) {
            throw ex;
        } catch (Exception ex) {
            record.markTimeout();
            metrics.timeout(functionName);
            return new InvocationResponse(record.executionId(), "timeout", null, null);
        }
    }

    public Mono<InvocationResponse> invokeSyncReactive(String functionName,
                                                        InvocationRequest request,
                                                        String idempotencyKey,
                                                        String traceId,
                                                        Integer timeoutOverrideMs) {
        enforceRateLimit();

        FunctionSpec spec = functionService.get(functionName).orElseThrow(FunctionNotFoundException::new);
        ExecutionLookup lookup = createOrReuseExecution(functionName, spec, request, idempotencyKey, traceId);
        ExecutionRecord record = lookup.record();

        if (record.state() == ExecutionState.SUCCESS || record.state() == ExecutionState.ERROR) {
            InvocationResult result = record.lastError() == null
                    ? InvocationResult.success(record.output())
                    : new InvocationResult(false, null, record.lastError());
            return Mono.just(toResponse(record, result));
        }

        if (lookup.isNew()) {
            try {
                if (syncQueueEnabled()) {
                    syncQueueGateway.enqueueOrThrow(record.task());
                } else if (enqueuer.enabled()) {
                    enqueueOrThrow(record);
                } else {
                    dispatch(record.task());
                }
            } catch (RuntimeException ex) {
                return Mono.error(ex);
            }
        }

        int timeoutMs = timeoutOverrideMs == null ? spec.timeoutMs() : timeoutOverrideMs;
        return Mono.fromFuture(record.completion())
                .timeout(Duration.ofMillis(timeoutMs))
                .map(result -> {
                    if (result.error() != null && "QUEUE_TIMEOUT".equals(result.error().code())) {
                        throw new SyncQueueRejectedException(SyncQueueRejectReason.TIMEOUT, syncQueueRetryAfterSeconds());
                    }
                    return toResponse(record, result);
                })
                .onErrorResume(java.util.concurrent.TimeoutException.class, ex -> {
                    record.markTimeout();
                    metrics.timeout(functionName);
                    return Mono.just(new InvocationResponse(record.executionId(), "timeout", null, null));
                });
    }

    public InvocationResponse invokeAsync(String functionName,
                                          InvocationRequest request,
                                          String idempotencyKey,
                                          String traceId) {
        enforceRateLimit();

        FunctionSpec spec = functionService.get(functionName).orElseThrow(FunctionNotFoundException::new);
        if (!enqueuer.enabled()) {
            throw new AsyncQueueUnavailableException();
        }

        ExecutionLookup lookup = createOrReuseExecution(functionName, spec, request, idempotencyKey, traceId);
        ExecutionRecord record = lookup.record();

        if (lookup.isNew()) {
            enqueueOrThrow(record);
        }
        return new InvocationResponse(record.executionId(), "queued", null, null);
    }

    public Optional<ExecutionStatus> getStatus(String executionId) {
        return executionStore.get(executionId).map(this::toStatus);
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

    private void releaseDispatchSlot(String functionName) {
        enqueuer.decrementInFlight(functionName);
        enqueuer.releaseSlot(functionName);
    }

    /**
     * Overload for backward compatibility (e.g., callback completions without cold start info).
     */
    public void completeExecution(String executionId, InvocationResult result) {
        completeExecution(executionId, DispatchResult.warm(result));
    }

    private void enforceRateLimit() {
        if (!rateLimiter.allow()) {
            throw new RateLimitException();
        }
    }

    private ExecutionLookup createOrReuseExecution(String functionName,
                                                   FunctionSpec spec,
                                                   InvocationRequest request,
                                                   String idempotencyKey,
                                                   String traceId) {
        String executionId = UUID.randomUUID().toString();
        boolean idempotencyStored = false;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String existingExecutionId = idempotencyStore.putIfAbsent(functionName, idempotencyKey, executionId);
            if (existingExecutionId != null) {
                ExecutionRecord existing = executionStore.getOrNull(existingExecutionId);
                if (existing != null) {
                    return new ExecutionLookup(existing, false);
                }
                // Stale idempotency mapping pointing to an evicted execution.
                idempotencyStore.put(functionName, idempotencyKey, executionId);
            } else {
                idempotencyStored = true;
            }
        }

        InvocationTask task = new InvocationTask(
                executionId,
                functionName,
                spec,
                request,
                idempotencyKey,
                traceId,
                Instant.now(),
                1
        );
        ExecutionRecord record = new ExecutionRecord(executionId, task);
        executionStore.put(record);
        if (!idempotencyStored && idempotencyKey != null && !idempotencyKey.isBlank()) {
            // Keep mapping fresh for TTL semantics.
            idempotencyStore.put(functionName, idempotencyKey, executionId);
        }
        return new ExecutionLookup(record, true);
    }

    private void enqueueOrThrow(ExecutionRecord record) {
        boolean enqueued = enqueuer.enqueue(record.task());
        if (!enqueued) {
            metrics.queueRejected(record.task().functionName());
            throw new QueueFullException();
        }
        metrics.enqueue(record.task().functionName());
    }

    private InvocationResponse toResponse(ExecutionRecord record, InvocationResult result) {
        String status = result.success() ? "success" : "error";
        return new InvocationResponse(record.executionId(), status, result.output(), result.error());
    }

    private boolean syncQueueEnabled() {
        return syncQueueGateway.enabled();
    }

    private int syncQueueRetryAfterSeconds() {
        return syncQueueGateway.retryAfterSeconds();
    }

    private ExecutionStatus toStatus(ExecutionRecord record) {
        // Use snapshot for consistent read of all fields
        ExecutionRecord.Snapshot snapshot = record.snapshot();
        String status = snapshot.state().name().toLowerCase();
        return new ExecutionStatus(
                snapshot.executionId(),
                status,
                snapshot.startedAt(),
                snapshot.finishedAt(),
                snapshot.output(),
                snapshot.lastError(),
                snapshot.coldStart(),
                snapshot.initDurationMs()
        );
    }

    private record ExecutionLookup(ExecutionRecord record, boolean isNew) {
    }
}
