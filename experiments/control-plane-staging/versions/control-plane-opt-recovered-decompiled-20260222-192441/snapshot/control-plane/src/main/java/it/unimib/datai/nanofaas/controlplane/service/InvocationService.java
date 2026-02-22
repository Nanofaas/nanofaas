package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionStatus;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
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

    private final FunctionService functionService;
    private final InvocationEnqueuer enqueuer;
    private final ExecutionStore executionStore;
    private final IdempotencyStore idempotencyStore;
    private final RateLimiter rateLimiter;
    private final Metrics metrics;
    private final SyncQueueGateway syncQueueGateway;
    private final ExecutionCompletionHandler completionHandler;

    public InvocationService(FunctionService functionService,
                             @Nullable InvocationEnqueuer enqueuer,
                             ExecutionStore executionStore,
                             IdempotencyStore idempotencyStore,
                             RateLimiter rateLimiter,
                             Metrics metrics,
                             @Autowired(required = false) @Nullable SyncQueueGateway syncQueueGateway,
                             ExecutionCompletionHandler completionHandler) {
        this.functionService = functionService;
        this.enqueuer = enqueuer == null ? InvocationEnqueuer.noOp() : enqueuer;
        this.executionStore = executionStore;
        this.idempotencyStore = idempotencyStore;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.syncQueueGateway = syncQueueGateway == null ? SyncQueueGateway.noOp() : syncQueueGateway;
        this.completionHandler = completionHandler;
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
        completionHandler.dispatch(task);
    }

    public void completeExecution(String executionId, DispatchResult dispatchResult) {
        completionHandler.completeExecution(executionId, dispatchResult);
    }

    public void completeExecution(String executionId, InvocationResult result) {
        completionHandler.completeExecution(executionId, result);
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
