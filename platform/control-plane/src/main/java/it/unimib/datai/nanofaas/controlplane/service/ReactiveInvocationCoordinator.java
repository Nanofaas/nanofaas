package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.WaiterCapacity;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.Outcome;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadContext;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadFailedException;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadGateway;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadTrigger;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
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
@SuppressWarnings("FutureReturnValueIgnored") // Completion is observed through the shared execution record.
public final class ReactiveInvocationCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ReactiveInvocationCoordinator.class);

    private final InvocationEnqueuer enqueuer;
    private final Metrics metrics;
    private final SyncQueueGateway syncQueueGateway;
    private final OffloadGateway offloadGateway;
    private final ExecutionCompletionHandler completionHandler;
    private final InvocationResponseMapper responseMapper;
    private final WaiterCapacity waiterCapacity;

    @org.springframework.beans.factory.annotation.Autowired
    public ReactiveInvocationCoordinator(@Nullable InvocationEnqueuer enqueuer,
                                         Metrics metrics,
                                         @Nullable SyncQueueGateway syncQueueGateway,
                                         @Nullable OffloadGateway offloadGateway,
                                         ExecutionCompletionHandler completionHandler,
                                         InvocationResponseMapper responseMapper,
                                         WaiterCapacity waiterCapacity) {
        this.enqueuer = enqueuer == null ? InvocationEnqueuer.noOp() : enqueuer;
        this.metrics = metrics;
        this.syncQueueGateway = syncQueueGateway == null ? SyncQueueGateway.noOp() : syncQueueGateway;
        this.offloadGateway = offloadGateway == null ? OffloadGateway.noOp() : offloadGateway;
        this.completionHandler = completionHandler;
        this.responseMapper = responseMapper;
        this.waiterCapacity = waiterCapacity;
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
        WaiterCapacity.Waiter waiter = reserveWaiter(lookup, spec);
        try {
            return invokeAttached(lookup, spec, timeoutOverrideMs, offloadContext)
                    .doFinally(ignored -> waiter.close());
        } catch (RuntimeException | Error failure) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
            waiter.close();
            throw failure;
        }
    }

    private WaiterCapacity.Waiter reserveWaiter(
            InvocationExecutionFactory.ExecutionLookup lookup, FunctionSpec spec) {
        try {
            ExecutionRecord executionRecord = lookup.executionRecord();
            if (executionRecord != null) {
                if (executionRecord.currentGeneration() != null) {
                    return waiterCapacity.reserve(executionRecord.currentGeneration(), executionRecord.executionId());
                }
                return waiterCapacity.reserve(spec.name(), executionRecord.executionId());
            }
            return waiterCapacity.reserve(spec.name(), lookup.settledExecutionId());
        } catch (RuntimeException | Error failure) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
            if (lookup.isNew()) {
                lookup.abandonAdmission();
            }
            throw failure;
        }
    }

    private Mono<SyncInvocation> invokeAttached(
            InvocationExecutionFactory.ExecutionLookup lookup,
            FunctionSpec spec,
            Integer timeoutOverrideMs,
            OffloadContext offloadContext) {
        // The key found an execution that is already over: the mutable record is gone,
        // but the outcome the replay needs is not. Retaining it is why
        // ExecutionRecord.toOutcome() keeps the payload for keyed executions.
        Outcome settled = lookup.settledOutcome();
        if (settled != null) {
            return Mono.just(SyncInvocation.local(
                    responseMapper.terminalResponse(lookup.settledExecutionId(), settled)));
        }

        // The execution concluded but its outcome payload was evicted: the replay does
        // not re-run the function, it returns 410 Gone. This must be checked before
        // dereferencing executionRecord, which the factory leaves null on this branch.
        if (lookup.gone()) {
            return Mono.error(new OutcomeGoneException(lookup.settledExecutionId()));
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
                    () -> admit(executionRecord, spec, offloadContext, offloadedTarget));
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
                    // Per-waiter timeout (ADR 0001 §5, invariant I1): this waiter's own budget
                    // elapsed, so only its wait ends with the documented 408/timeout response.
                    // The shared record, key, store, lease, budget and counters are untouched;
                    // the shared execution keeps running and its real result is what a later
                    // poll/replay observes. The waiter timeout is recorded on its own counter,
                    // never as a backend error.
                    metrics.timeout(executionRecord.task().functionName());
                    return Mono.just(new SyncInvocation(responseMapper.timeoutResponse(executionRecord), offloadedTarget.get()));
                })
                .onErrorResume(ex -> !(ex instanceof SyncQueueRejectedException) && !(ex instanceof OffloadFailedException), ex -> {
                    log.warn("Execution {} completed exceptionally", executionRecord.executionId(), ex);
                    String message = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                    InvocationResult failure = InvocationResult.error("EXECUTION_FAILED", message);
                    // Conclude the execution through the completion handler, not by marking the
                    // record terminal here: every terminal marker must conclude the shared future
                    // and settle the store (invariant I1/I3), and this branch used to mark the
                    // record ERROR while leaving it un-settled (P05). completeExecution is a no-op
                    // on an already-settled record, so the waiter still gets the error response.
                    completionHandler.completeExecution(executionRecord.executionId(), failure);
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
                       AtomicReference<String> offloadedTarget) {
        boolean offloadable = !context.offloadedHop() && offloadGateway.enabled();
        if (offloadable && offloadGateway.shouldOffloadEagerly(spec)) {
            startOffload(executionRecord, spec, OffloadTrigger.EAGER, context, offloadedTarget);
            return;
        }
        try {
            admitLocally(executionRecord);
        } catch (SyncQueueRejectedException ex) {
            OffloadTrigger trigger = pressureTrigger(ex.reason());
            if (offloadable && trigger != null && offloadGateway.shouldOffloadOnPressure(spec)) {
                startOffload(executionRecord, spec, trigger, context, offloadedTarget);
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
        if (syncQueueGateway.enabled() || enqueuer.queueStrategy() != InvocationEnqueuer.QueueStrategy.FUNCTION_QUEUE) {
            return false;
        }
        return !offloadGateway.enabled() || !offloadGateway.shouldOffloadEagerly(spec);
    }

    private void admitLocally(ExecutionRecord executionRecord) {
        if (syncQueueGateway.enabled()) {
            InvocationTask queuedTask = executionRecord.prepareForQueue();
            try {
                syncQueueGateway.enqueueOrThrow(queuedTask);
            } catch (RuntimeException | Error failure) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
                queuedTask.releaseQueuedInput();
                throw failure;
            }
        } else if (enqueuer.queueStrategy() == InvocationEnqueuer.QueueStrategy.FUNCTION_QUEUE) {
            InvocationEnqueueSupport.enqueueOrThrow(enqueuer::enqueue, metrics, executionRecord);
        } else {
            metrics.admitted(executionRecord.task().functionName(), executionRecord.task().kind());
            // Direct admission: the core acquires the capacity lease and bounds the work;
            // no room -> QueueFullException (429), never an implicit unbounded queue.
            completionHandler.dispatchDirect(executionRecord.task());
        }
    }

    private void startOffload(ExecutionRecord executionRecord,
                              FunctionSpec spec,
                              OffloadTrigger trigger,
                              OffloadContext context,
                              AtomicReference<String> offloadedTarget) {
        String target = offloadGateway.targetUrl(spec);
        offloadedTarget.set(target);
        // Bypasses the local queue entirely: no local concurrency slots are consumed,
        // so completion goes through the offload-specific path (no slot release, no retry).
        ExecutionRecord.PhysicalInput physicalInput;
        synchronized (executionRecord) { // NOSONAR (java:S2445): this object is its own monitor by design; every path locks the same instance
            if (executionRecord.isTerminal()) return;
            // Not try-with-resources (java:S2095): the remote call owns the input until its
            // terminal signal; it is closed on a failed invokeRemote or in whenComplete below.
            physicalInput = executionRecord.openPhysicalInput(executionRecord.task()); // NOSONAR
        }
        java.util.concurrent.CompletableFuture<InvocationResult> remote;
        try {
            remote = offloadGateway.invokeRemote(
                    physicalInput.task(), trigger, context, spec.timeoutMs()).toFuture();
        } catch (RuntimeException | Error failure) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
            physicalInput.close();
            throw failure;
        }
        // Administrative cancellation concludes only the local execution view. The raw remote
        // publisher may ignore cancellation and still retain the physical request, so it owns
        // the input until its own terminal signal rather than until this cancelable view closes.
        java.util.concurrent.CompletableFuture<Void> cancellationView = new java.util.concurrent.CompletableFuture<>();
        executionRecord.attachDispatchHandle(cancellationView);
        remote.whenComplete((result, ex) -> {
            try {
                if (ex == null) {
                    completionHandler.completeOffloadedExecution(executionRecord.executionId(), result);
                } else {
                    String detail = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                    OffloadFailedException failure = ex instanceof OffloadFailedException ofe
                            ? ofe : new OffloadFailedException(target, false, detail);
                    completionHandler.failOffloadedExecution(executionRecord.executionId(), failure);
                }
            } finally {
                try {
                    physicalInput.close();
                } finally {
                    cancellationView.complete(null);
                }
            }
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
