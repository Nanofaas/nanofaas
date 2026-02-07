package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.ExecutionStatus;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.queue.QueueFullException;
import it.unimib.datai.nanofaas.controlplane.queue.QueueManager;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionNotFoundException;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectedException;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
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
    private final QueueManager queueManager;
    private final ExecutionStore executionStore;
    private final IdempotencyStore idempotencyStore;
    private final DispatcherRouter dispatcherRouter;
    private final RateLimiter rateLimiter;
    private final Metrics metrics;
    private final SyncQueueService syncQueueService;

    public InvocationService(FunctionService functionService,
                             QueueManager queueManager,
                             ExecutionStore executionStore,
                             IdempotencyStore idempotencyStore,
                             DispatcherRouter dispatcherRouter,
                             RateLimiter rateLimiter,
                             Metrics metrics,
                             SyncQueueService syncQueueService) {
        this.functionService = functionService;
        this.queueManager = queueManager;
        this.executionStore = executionStore;
        this.idempotencyStore = idempotencyStore;
        this.dispatcherRouter = dispatcherRouter;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.syncQueueService = syncQueueService;
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
            if (syncQueueService.enabled()) {
                syncQueueService.enqueueOrThrow(record.task());
            } else {
                enqueueOrThrow(record);
            }
        }

        int timeoutMs = timeoutOverrideMs == null ? spec.timeoutMs() : timeoutOverrideMs;
        try {
            InvocationResult result = record.completion().get(timeoutMs, TimeUnit.MILLISECONDS);
            if (result.error() != null && "QUEUE_TIMEOUT".equals(result.error().code())) {
                throw new SyncQueueRejectedException(SyncQueueRejectReason.TIMEOUT, syncQueueService.retryAfterSeconds());
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
            if (syncQueueService.enabled()) {
                syncQueueService.enqueueOrThrow(record.task());
            } else {
                enqueueOrThrow(record);
            }
        }

        int timeoutMs = timeoutOverrideMs == null ? spec.timeoutMs() : timeoutOverrideMs;
        return Mono.fromFuture(record.completion())
                .timeout(Duration.ofMillis(timeoutMs))
                .map(result -> {
                    if (result.error() != null && "QUEUE_TIMEOUT".equals(result.error().code())) {
                        throw new SyncQueueRejectedException(SyncQueueRejectReason.TIMEOUT, syncQueueService.retryAfterSeconds());
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
        ExecutionRecord record = executionStore.get(task.executionId()).orElse(null);
        if (record == null) {
            queueManager.decrementInFlight(task.functionName());
            return;
        }

        record.markRunning();
        metrics.dispatch(task.functionName());

        ExecutionMode mode = task.functionSpec().executionMode();
        if (mode == ExecutionMode.LOCAL || mode == ExecutionMode.POOL) {
            java.util.concurrent.CompletableFuture<InvocationResult> future = (mode == ExecutionMode.LOCAL)
                    ? dispatcherRouter.dispatchLocal(task)
                    : dispatcherRouter.dispatchPool(task);

            future.whenComplete((result, error) -> {
                if (error != null) {
                    completeExecution(task.executionId(), InvocationResult.error(mode.name() + "_ERROR", error.getMessage()));
                } else {
                    completeExecution(task.executionId(), result);
                }
            });
        } else {
            // REMOTE dispatch
            dispatcherRouter.dispatchRemote(task).whenComplete((result, error) -> {
                if (error != null) {
                    completeExecution(task.executionId(), InvocationResult.error("DISPATCH_ERROR", error.getMessage()));
                }
            });
        }
    }

    public void completeExecution(String executionId, InvocationResult result) {
        ExecutionRecord record = executionStore.get(executionId).orElse(null);
        if (record == null) {
            return;
        }

        queueManager.decrementInFlight(record.task().functionName());

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

        // No retry - complete the execution atomically
        ExecutionRecord.Snapshot beforeComplete = record.snapshot();
        if (result.success()) {
            record.markSuccess(result.output());
            metrics.success(record.task().functionName());
        } else {
            record.markError(result.error());
            metrics.error(record.task().functionName());
        }

        ExecutionRecord.Snapshot afterComplete = record.snapshot();
        if (beforeComplete.startedAt() != null && afterComplete.finishedAt() != null) {
            long durationMs = afterComplete.finishedAt().toEpochMilli() - beforeComplete.startedAt().toEpochMilli();
            metrics.latency(record.task().functionName()).record(durationMs, TimeUnit.MILLISECONDS);
        }

        record.completion().complete(result);
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
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<String> existingId = idempotencyStore.getExecutionId(functionName, idempotencyKey);
            if (existingId.isPresent()) {
                ExecutionRecord existing = executionStore.get(existingId.get()).orElse(null);
                if (existing != null) {
                    return new ExecutionLookup(existing, false);
                }
            }
        }

        String executionId = UUID.randomUUID().toString();
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
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyStore.put(functionName, idempotencyKey, executionId);
        }
        return new ExecutionLookup(record, true);
    }

    private void enqueueOrThrow(ExecutionRecord record) {
        boolean enqueued = queueManager.enqueue(record.task());
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
                snapshot.lastError()
        );
    }

    private record ExecutionLookup(ExecutionRecord record, boolean isNew) {
    }
}
