package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadContext;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadFailedException;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadGateway;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadTrigger;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

@Service
public final class ReactiveInvocationCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ReactiveInvocationCoordinator.class);

    private final InvocationEnqueuer enqueuer;
    private final Metrics metrics;
    private final SyncQueueGateway syncQueueGateway;
    private final OffloadGateway offloadGateway;
    private final ExecutionCompletionHandler completionHandler;
    private final InvocationResponseMapper responseMapper;

    public ReactiveInvocationCoordinator(@Nullable InvocationEnqueuer enqueuer,
                                         Metrics metrics,
                                         @Nullable SyncQueueGateway syncQueueGateway,
                                         @Nullable OffloadGateway offloadGateway,
                                         ExecutionCompletionHandler completionHandler,
                                         InvocationResponseMapper responseMapper) {
        this.enqueuer = enqueuer == null ? InvocationEnqueuer.noOp() : enqueuer;
        this.metrics = metrics;
        this.syncQueueGateway = syncQueueGateway == null ? SyncQueueGateway.noOp() : syncQueueGateway;
        this.offloadGateway = offloadGateway == null ? OffloadGateway.noOp() : offloadGateway;
        this.completionHandler = completionHandler;
        this.responseMapper = responseMapper;
    }

    public Mono<SyncInvocation> invoke(InvocationExecutionFactory.ExecutionLookup lookup,
                                       FunctionSpec spec,
                                       Integer timeoutOverrideMs) {
        return invoke(lookup, spec, timeoutOverrideMs, OffloadContext.none());
    }

    public Mono<SyncInvocation> invoke(InvocationExecutionFactory.ExecutionLookup lookup,
                                       FunctionSpec spec,
                                       Integer timeoutOverrideMs,
                                       OffloadContext offloadContext) {
        ExecutionRecord record = lookup.record();
        InvocationResponse replay = responseMapper.terminalResponse(record);
        if (replay != null) {
            return Mono.just(SyncInvocation.local(replay));
        }

        int timeoutMs = timeoutOverrideMs == null ? spec.timeoutMs() : timeoutOverrideMs;
        AtomicReference<String> offloadedTarget = new AtomicReference<>();
        try {
            InvocationEnqueueSupport.admitIfNew(lookup,
                    () -> admit(record, spec, offloadContext, timeoutMs, offloadedTarget));
        } catch (RuntimeException ex) {
            return Mono.error(ex);
        }

        // suppressCancel=true: a single subscriber's timeout/disconnect must not cancel
        // the shared completion future other idempotent waiters depend on.
        return Mono.fromFuture(record.completion(), true)
                // offload failures complete the future exceptionally; make sure the
                // typed exception survives any CompletionException wrapping
                .onErrorMap(java.util.concurrent.CompletionException.class,
                        ex -> ex.getCause() != null ? ex.getCause() : ex)
                .timeout(Duration.ofMillis(timeoutMs))
                .map(result -> {
                    if (result.error() != null && "QUEUE_TIMEOUT".equals(result.error().code())) {
                        throw new SyncQueueRejectedException(SyncQueueRejectReason.TIMEOUT, syncQueueGateway.retryAfterSeconds());
                    }
                    return new SyncInvocation(responseMapper.toResponse(record, result), offloadedTarget.get());
                })
                .onErrorResume(java.util.concurrent.TimeoutException.class, ex -> {
                    record.markTimeout();
                    metrics.timeout(record.task().functionName());
                    return Mono.just(new SyncInvocation(responseMapper.timeoutResponse(record), offloadedTarget.get()));
                })
                .onErrorResume(ex -> !(ex instanceof SyncQueueRejectedException) && !(ex instanceof OffloadFailedException), ex -> {
                    log.warn("Execution {} completed exceptionally", record.executionId(), ex);
                    String message = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                    InvocationResult failure = InvocationResult.error("EXECUTION_FAILED", message);
                    record.markError(failure.error());
                    metrics.error(record.task().functionName());
                    return Mono.just(new SyncInvocation(responseMapper.toResponse(record, failure), offloadedTarget.get()));
                });
    }

    /**
     * Admission action: offload eagerly (per-function policy), else try local
     * admission; on sync-queue pressure rejection (DEPTH/EST_WAIT) offload instead
     * of propagating the 429. Requests received via offload are never re-offloaded.
     */
    private void admit(ExecutionRecord record,
                       FunctionSpec spec,
                       OffloadContext context,
                       int timeoutMs,
                       AtomicReference<String> offloadedTarget) {
        boolean offloadable = !context.offloadedHop() && offloadGateway.enabled();
        if (offloadable && offloadGateway.shouldOffloadEagerly(spec)) {
            startOffload(record, spec, OffloadTrigger.EAGER, context, timeoutMs, offloadedTarget);
            return;
        }
        try {
            admitLocally(record);
        } catch (SyncQueueRejectedException ex) {
            OffloadTrigger trigger = pressureTrigger(ex.reason());
            if (offloadable && trigger != null && offloadGateway.shouldOffloadOnPressure(spec, ex.reason())) {
                startOffload(record, spec, trigger, context, timeoutMs, offloadedTarget);
                return;
            }
            throw ex;
        }
    }

    private void admitLocally(ExecutionRecord record) {
        if (syncQueueGateway.enabled()) {
            syncQueueGateway.enqueueOrThrow(record.task());
        } else if (enqueuer.enabled()) {
            InvocationEnqueueSupport.enqueueOrThrow(enqueuer, metrics, record);
        } else {
            completionHandler.dispatch(record.task());
        }
    }

    private void startOffload(ExecutionRecord record,
                              FunctionSpec spec,
                              OffloadTrigger trigger,
                              OffloadContext context,
                              int timeoutMs,
                              AtomicReference<String> offloadedTarget) {
        String target = offloadGateway.targetUrl(spec);
        offloadedTarget.set(target);
        // Bypasses the local queue entirely: no local concurrency slots are consumed,
        // so completion goes through the offload-specific path (no slot release, no retry).
        offloadGateway.invokeRemote(record.task(), trigger, context, timeoutMs)
                .subscribe(
                        result -> completionHandler.completeOffloadedExecution(record.executionId(), result),
                        ex -> {
                            OffloadFailedException failure = ex instanceof OffloadFailedException ofe
                                    ? ofe
                                    : new OffloadFailedException(target, false,
                                            ex.getMessage() != null ? ex.getMessage() : ex.toString());
                            completionHandler.failOffloadedExecution(record.executionId(), failure);
                        });
    }

    private static OffloadTrigger pressureTrigger(SyncQueueRejectReason reason) {
        return switch (reason) {
            case DEPTH -> OffloadTrigger.DEPTH;
            case EST_WAIT -> OffloadTrigger.EST_WAIT;
            case TIMEOUT -> null;
        };
    }
}
