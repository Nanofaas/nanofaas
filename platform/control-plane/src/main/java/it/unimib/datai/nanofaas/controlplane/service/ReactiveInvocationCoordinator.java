package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.Outcome;
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
    private final ExecutionStore executionStore;

    public ReactiveInvocationCoordinator(@Nullable InvocationEnqueuer enqueuer,
                                         Metrics metrics,
                                         @Nullable SyncQueueGateway syncQueueGateway,
                                         @Nullable OffloadGateway offloadGateway,
                                         ExecutionCompletionHandler completionHandler,
                                         InvocationResponseMapper responseMapper,
                                         ExecutionStore executionStore) {
        this.enqueuer = enqueuer == null ? InvocationEnqueuer.noOp() : enqueuer;
        this.metrics = metrics;
        this.syncQueueGateway = syncQueueGateway == null ? SyncQueueGateway.noOp() : syncQueueGateway;
        this.offloadGateway = offloadGateway == null ? OffloadGateway.noOp() : offloadGateway;
        this.completionHandler = completionHandler;
        this.responseMapper = responseMapper;
        this.executionStore = executionStore;
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
        // La chiave ha trovato un'esecuzione gia' finita: il record mutabile non
        // esiste piu', ma l'esito che serve al replay si'. Trattenerlo e' il motivo
        // per cui Outcome.of() conserva il payload per le esecuzioni con chiave.
        Outcome settled = lookup.settledOutcome();
        if (settled != null) {
            return Mono.just(SyncInvocation.local(
                    responseMapper.terminalResponse(lookup.settledExecutionId(), settled)));
        }

        ExecutionRecord executionRecord = lookup.executionRecord();
        InvocationResponse replay = responseMapper.terminalResponse(executionRecord);
        if (replay != null) {
            return Mono.just(SyncInvocation.local(replay));
        }

        int timeoutMs = timeoutOverrideMs == null ? spec.timeoutMs() : timeoutOverrideMs;
        AtomicReference<String> offloadedTarget = new AtomicReference<>();
        try {
            InvocationEnqueueSupport.admitIfNew(lookup,
                    () -> admit(executionRecord, spec, offloadContext, timeoutMs, offloadedTarget));
        } catch (RuntimeException ex) {
            return Mono.error(ex);
        }

        // suppressCancel=true: a single subscriber's timeout/disconnect must not cancel
        // the shared completion future other idempotent waiters depend on.
        return Mono.fromFuture(executionRecord.completion(), true)
                // offload failures complete the future exceptionally; make sure the
                // typed exception survives any CompletionException wrapping
                .onErrorMap(java.util.concurrent.CompletionException.class,
                        ex -> ex.getCause() != null ? ex.getCause() : ex)
                .timeout(Duration.ofMillis(timeoutMs))
                .map(result -> {
                    if (result.error() != null && "QUEUE_TIMEOUT".equals(result.error().code())) {
                        throw new SyncQueueRejectedException(SyncQueueRejectReason.TIMEOUT, syncQueueGateway.retryAfterSeconds());
                    }
                    return new SyncInvocation(responseMapper.toResponse(executionRecord, result), offloadedTarget.get());
                })
                .onErrorResume(java.util.concurrent.TimeoutException.class, ex -> {
                    executionRecord.markTimeout();
                    // Marcato ma NON archiviato: il dispatch e' ancora in volo e
                    // tiene uno slot di concorrenza. Archiviarlo adesso lo toglierebbe
                    // dai vivi, e il completamento che arriva dopo non lo troverebbe
                    // piu' per restituire quello slot. Archivia quel completamento,
                    // che passa comunque di li'; se non arrivasse mai, ci pensa
                    // maxLifetime.
                    metrics.timeout(executionRecord.task().functionName());
                    return Mono.just(new SyncInvocation(responseMapper.timeoutResponse(executionRecord), offloadedTarget.get()));
                })
                .onErrorResume(ex -> !(ex instanceof SyncQueueRejectedException) && !(ex instanceof OffloadFailedException), ex -> {
                    log.warn("Execution {} completed exceptionally", executionRecord.executionId(), ex);
                    String message = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                    InvocationResult failure = InvocationResult.error("EXECUTION_FAILED", message);
                    // Stessa ragione del timeout qui sopra: lo slot prima dell'archivio.
                    executionRecord.markError(failure.error());
                    metrics.error(executionRecord.task().functionName());
                    return Mono.just(new SyncInvocation(responseMapper.toResponse(executionRecord, failure), offloadedTarget.get()));
                });
    }

    /**
     * Admission action: offload eagerly (per-function policy), else try local
     * admission; on sync-queue pressure rejection (DEPTH/EST_WAIT) offload instead
     * of propagating the 429. Requests received via offload are never re-offloaded.
     */
    private void admit(ExecutionRecord executionRecord,
                       FunctionSpec spec,
                       OffloadContext context,
                       int timeoutMs,
                       AtomicReference<String> offloadedTarget) {
        boolean offloadable = !context.offloadedHop() && offloadGateway.enabled();
        if (offloadable && offloadGateway.shouldOffloadEagerly(spec)) {
            startOffload(executionRecord, spec, OffloadTrigger.EAGER, context, timeoutMs, offloadedTarget);
            return;
        }
        try {
            admitLocally(executionRecord);
        } catch (SyncQueueRejectedException ex) {
            OffloadTrigger trigger = pressureTrigger(ex.reason());
            if (offloadable && trigger != null && offloadGateway.shouldOffloadOnPressure(spec)) {
                startOffload(executionRecord, spec, trigger, context, timeoutMs, offloadedTarget);
                return;
            }
            throw ex;
        }
    }

    /**
     * Whether a full async queue would certainly refuse this invocation, so a caller may
     * skip building an execution it is about to abandon. False wherever admission can
     * end somewhere other than that queue: the sync queue owns admission when loaded,
     * and an eagerly offloaded function never reaches a local queue at all. Pressure
     * offload is not a case here - it triggers on SyncQueueRejectedException, which the
     * async queue never throws.
     */
    public boolean queueFullMeansRefusal(FunctionSpec spec) {
        if (syncQueueGateway.enabled() || !enqueuer.enabled()) {
            return false;
        }
        return !offloadGateway.enabled() || !offloadGateway.shouldOffloadEagerly(spec);
    }

    private void admitLocally(ExecutionRecord executionRecord) {
        if (syncQueueGateway.enabled()) {
            syncQueueGateway.enqueueOrThrow(executionRecord.task());
        } else if (enqueuer.enabled()) {
            InvocationEnqueueSupport.enqueueOrThrow(enqueuer, metrics, executionRecord);
        } else {
            metrics.admitted(executionRecord.task().functionName(), executionRecord.task().kind());
            completionHandler.dispatch(executionRecord.task());
        }
    }

    private void startOffload(ExecutionRecord executionRecord,
                              FunctionSpec spec,
                              OffloadTrigger trigger,
                              OffloadContext context,
                              int timeoutMs,
                              AtomicReference<String> offloadedTarget) {
        String target = offloadGateway.targetUrl(spec);
        offloadedTarget.set(target);
        // Bypasses the local queue entirely: no local concurrency slots are consumed,
        // so completion goes through the offload-specific path (no slot release, no retry).
        offloadGateway.invokeRemote(executionRecord.task(), trigger, context, timeoutMs)
                .subscribe(
                        result -> completionHandler.completeOffloadedExecution(executionRecord.executionId(), result),
                        ex -> {
                            String detail = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                            OffloadFailedException failure = ex instanceof OffloadFailedException ofe
                                    ? ofe
                                    : new OffloadFailedException(target, false, detail);
                            completionHandler.failOffloadedExecution(executionRecord.executionId(), failure);
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
