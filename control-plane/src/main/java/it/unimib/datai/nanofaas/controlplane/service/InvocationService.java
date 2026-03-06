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
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
public class InvocationService {

    private final FunctionService functionService;
    private final InvocationEnqueuer enqueuer;
    private final ExecutionStore executionStore;
    private final RateLimiter rateLimiter;
    private final Metrics metrics;
    private final SyncQueueGateway syncQueueGateway;
    private final ExecutionCompletionHandler completionHandler;
    private final InvocationExecutionFactory executionFactory;
    private final InvocationResponseMapper responseMapper;

    public InvocationService(FunctionService functionService,
                             @Nullable InvocationEnqueuer enqueuer,
                             ExecutionStore executionStore,
                             IdempotencyStore idempotencyStore,
                             RateLimiter rateLimiter,
                             Metrics metrics,
                             @Autowired(required = false) @Nullable SyncQueueGateway syncQueueGateway,
                             ExecutionCompletionHandler completionHandler) {
        this(
                functionService,
                enqueuer,
                executionStore,
                rateLimiter,
                metrics,
                syncQueueGateway,
                completionHandler,
                new InvocationExecutionFactory(executionStore, idempotencyStore),
                new InvocationResponseMapper()
        );
    }

    @Autowired
    public InvocationService(FunctionService functionService,
                             @Nullable InvocationEnqueuer enqueuer,
                             ExecutionStore executionStore,
                             RateLimiter rateLimiter,
                             Metrics metrics,
                             @Autowired(required = false) @Nullable SyncQueueGateway syncQueueGateway,
                             ExecutionCompletionHandler completionHandler,
                             InvocationExecutionFactory executionFactory,
                             InvocationResponseMapper responseMapper) {
        this.functionService = functionService;
        this.enqueuer = enqueuer == null ? InvocationEnqueuer.noOp() : enqueuer;
        this.executionStore = executionStore;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.syncQueueGateway = syncQueueGateway == null ? SyncQueueGateway.noOp() : syncQueueGateway;
        this.completionHandler = completionHandler;
        this.executionFactory = executionFactory;
        this.responseMapper = responseMapper;
    }

    public InvocationResponse invokeSync(String functionName,
                                         InvocationRequest request,
                                         String idempotencyKey,
                                         String traceId,
                                         Integer timeoutOverrideMs) throws InterruptedException {
        enforceRateLimit();

        FunctionSpec spec = functionService.get(functionName).orElseThrow(FunctionNotFoundException::new);
        InvocationExecutionFactory.ExecutionLookup lookup =
                executionFactory.createOrReuseExecution(functionName, spec, request, idempotencyKey, traceId);
        ExecutionRecord record = lookup.record();

        if (record.state() == ExecutionState.SUCCESS || record.state() == ExecutionState.ERROR) {
            InvocationResult result = record.lastError() == null
                    ? InvocationResult.success(record.output())
                    : new InvocationResult(false, null, record.lastError());
            return responseMapper.toResponse(record, result);
        }
        if (record.state() == ExecutionState.TIMEOUT) {
            return responseMapper.timeoutResponse(record);
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
            return responseMapper.toResponse(record, result);
        } catch (SyncQueueRejectedException ex) {
            throw ex;
        } catch (Exception ex) {
            record.markTimeout();
            metrics.timeout(functionName);
            return responseMapper.timeoutResponse(record);
        }
    }

    public Mono<InvocationResponse> invokeSyncReactive(String functionName,
                                                        InvocationRequest request,
                                                        String idempotencyKey,
                                                        String traceId,
                                                        Integer timeoutOverrideMs) {
        enforceRateLimit();

        FunctionSpec spec = functionService.get(functionName).orElseThrow(FunctionNotFoundException::new);
        InvocationExecutionFactory.ExecutionLookup lookup =
                executionFactory.createOrReuseExecution(functionName, spec, request, idempotencyKey, traceId);
        ExecutionRecord record = lookup.record();

        if (record.state() == ExecutionState.SUCCESS || record.state() == ExecutionState.ERROR) {
            InvocationResult result = record.lastError() == null
                    ? InvocationResult.success(record.output())
                    : new InvocationResult(false, null, record.lastError());
            return Mono.just(responseMapper.toResponse(record, result));
        }
        if (record.state() == ExecutionState.TIMEOUT) {
            return Mono.just(responseMapper.timeoutResponse(record));
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
                    return responseMapper.toResponse(record, result);
                })
                .onErrorResume(java.util.concurrent.TimeoutException.class, ex -> {
                    record.markTimeout();
                    metrics.timeout(functionName);
                    return Mono.just(responseMapper.timeoutResponse(record));
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

        InvocationExecutionFactory.ExecutionLookup lookup =
                executionFactory.createOrReuseExecution(functionName, spec, request, idempotencyKey, traceId);
        ExecutionRecord record = lookup.record();

        if (lookup.isNew()) {
            enqueueOrThrow(record);
        }
        return new InvocationResponse(record.executionId(), "queued", null, null);
    }

    public Optional<ExecutionStatus> getStatus(String executionId) {
        return executionStore.get(executionId).map(responseMapper::toStatus);
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

    private void enqueueOrThrow(ExecutionRecord record) {
        boolean enqueued = enqueuer.enqueue(record.task());
        if (!enqueued) {
            metrics.queueRejected(record.task().functionName());
            throw new QueueFullException();
        }
        metrics.enqueue(record.task().functionName());
    }

    private boolean syncQueueEnabled() {
        return syncQueueGateway.enabled();
    }

    private int syncQueueRetryAfterSeconds() {
        return syncQueueGateway.retryAfterSeconds();
    }
}
