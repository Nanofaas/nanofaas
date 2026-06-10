package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectedException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Service
public final class ReactiveInvocationCoordinator {
    private final InvocationEnqueuer enqueuer;
    private final Metrics metrics;
    private final SyncQueueGateway syncQueueGateway;
    private final ExecutionCompletionHandler completionHandler;
    private final InvocationResponseMapper responseMapper;

    public ReactiveInvocationCoordinator(@Nullable InvocationEnqueuer enqueuer,
                                         Metrics metrics,
                                         @Nullable SyncQueueGateway syncQueueGateway,
                                         ExecutionCompletionHandler completionHandler,
                                         InvocationResponseMapper responseMapper) {
        this.enqueuer = enqueuer == null ? InvocationEnqueuer.noOp() : enqueuer;
        this.metrics = metrics;
        this.syncQueueGateway = syncQueueGateway == null ? SyncQueueGateway.noOp() : syncQueueGateway;
        this.completionHandler = completionHandler;
        this.responseMapper = responseMapper;
    }

    public Mono<InvocationResponse> invoke(InvocationExecutionFactory.ExecutionLookup lookup,
                                           FunctionSpec spec,
                                           Integer timeoutOverrideMs) {
        ExecutionRecord record = lookup.record();
        InvocationResponse replay = responseMapper.terminalResponse(record);
        if (replay != null) {
            return Mono.just(replay);
        }

        if (lookup.isNew()) {
            try {
                if (syncQueueGateway.enabled()) {
                    syncQueueGateway.enqueueOrThrow(record.task());
                } else if (enqueuer.enabled()) {
                    InvocationEnqueueSupport.enqueueOrThrow(enqueuer, metrics, record);
                } else {
                    completionHandler.dispatch(record.task());
                }
                lookup.publishAdmission();
            } catch (RuntimeException ex) {
                lookup.abandonAdmission();
                return Mono.error(ex);
            }
        }

        int timeoutMs = timeoutOverrideMs == null ? spec.timeoutMs() : timeoutOverrideMs;
        // suppressCancel=true: a single subscriber's timeout/disconnect must not cancel
        // the shared completion future other idempotent waiters depend on.
        return Mono.fromFuture(record.completion(), true)
                .timeout(Duration.ofMillis(timeoutMs))
                .map(result -> {
                    if (result.error() != null && "QUEUE_TIMEOUT".equals(result.error().code())) {
                        throw new SyncQueueRejectedException(SyncQueueRejectReason.TIMEOUT, syncQueueGateway.retryAfterSeconds());
                    }
                    return responseMapper.toResponse(record, result);
                })
                .onErrorResume(java.util.concurrent.TimeoutException.class, ex -> {
                    record.markTimeout();
                    metrics.timeout(record.task().functionName());
                    return Mono.just(responseMapper.timeoutResponse(record));
                });
    }
}
