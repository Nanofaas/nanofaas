package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionStatus;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.queue.QueueFullException;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.execution.Outcome;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionNotFoundException;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadContext;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Optional;

@Service
public class InvocationService {

    private final FunctionService functionService;
    private final InvocationEnqueuer enqueuer;
    private final ExecutionStore executionStore;
    private final Metrics metrics;
    private final ExecutionCompletionHandler completionHandler;
    private final InvocationExecutionFactory executionFactory;
    private final InvocationResponseMapper responseMapper;
    private final ReactiveInvocationCoordinator reactiveCoordinator;

    public InvocationService(FunctionService functionService,
                             @Nullable InvocationEnqueuer enqueuer,
                             ExecutionStore executionStore,
                             IdempotencyStore idempotencyStore,
                             Metrics metrics,
                             @Autowired(required = false) @Nullable SyncQueueGateway syncQueueGateway,
                             ExecutionCompletionHandler completionHandler) {
        this(
                functionService,
                enqueuer,
                executionStore,
                metrics,
                completionHandler,
                new InvocationExecutionFactory(executionStore, idempotencyStore, metrics),
                new InvocationResponseMapper(),
                new ReactiveInvocationCoordinator(enqueuer, metrics, syncQueueGateway, null, completionHandler, new InvocationResponseMapper())
        );
    }

    @Autowired
    public InvocationService(FunctionService functionService,
                             @Nullable InvocationEnqueuer enqueuer,
                             ExecutionStore executionStore,
                             Metrics metrics,
                             ExecutionCompletionHandler completionHandler,
                             InvocationExecutionFactory executionFactory,
                             InvocationResponseMapper responseMapper,
                             ReactiveInvocationCoordinator reactiveCoordinator) {
        this.functionService = functionService;
        this.enqueuer = enqueuer == null ? InvocationEnqueuer.noOp() : enqueuer;
        this.executionStore = executionStore;
        this.metrics = metrics;
        this.completionHandler = completionHandler;
        this.executionFactory = executionFactory;
        this.responseMapper = responseMapper;
        this.reactiveCoordinator = reactiveCoordinator;
    }

    public Mono<SyncInvocation> invokeSyncReactive(String functionName,
                                                   InvocationRequest request,
                                                   String idempotencyKey,
                                                   String traceId,
                                                   Integer timeoutOverrideMs) {
        return invokeSyncReactive(functionName, request, idempotencyKey, traceId, timeoutOverrideMs, OffloadContext.none());
    }

    public Mono<SyncInvocation> invokeSyncReactive(String functionName,
                                                   InvocationRequest request,
                                                   String idempotencyKey,
                                                   String traceId,
                                                   Integer timeoutOverrideMs,
                                                   OffloadContext offloadContext) {
        record Prepared(FunctionSpec spec, InvocationExecutionFactory.ExecutionLookup lookup) {}
        Mono<Prepared> prepared = Mono.fromCallable(() -> {
            FunctionSpec spec = functionService.get(functionName).orElseThrow(FunctionNotFoundException::new);
            refuseEarlyIfQueueFull(functionName, spec, idempotencyKey, InvocationKind.SYNC);
            return new Prepared(spec,
                    executionFactory.createOrReuseExecution(functionName, spec, request, idempotencyKey, traceId,
                            InvocationKind.SYNC));
        });
        // Hop only when there is something to hop for. createOrReuseExecution parks
        // only inside its idempotency loop, and returns on its first branch without
        // touching a lock when there is no key; the rest of the block is a rate
        // check, a map lookup and a queue check. So an invocation without a key has
        // nothing that must leave the event loop, and sending it to boundedElastic
        // anyway costs a thread handoff per request - 6.1us measured against 0.085us
        // of work, seventy times the thing it protects.
        //
        // Requests that will be refused paid it too, which is the part worth
        // removing: deciding a 429 should not need a second thread.
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            prepared = prepared.subscribeOn(Schedulers.boundedElastic());
        }
        return prepared.flatMap(p -> reactiveCoordinator.invoke(p.lookup(), p.spec(), timeoutOverrideMs, offloadContext));
    }

    public InvocationResponse invokeAsync(String functionName,
                                          InvocationRequest request,
                                          String idempotencyKey,
                                          String traceId) {
        FunctionSpec spec = functionService.get(functionName).orElseThrow(FunctionNotFoundException::new);
        if (!enqueuer.enabled()) {
            throw new AsyncQueueUnavailableException();
        }
        refuseEarlyIfQueueFull(functionName, spec, idempotencyKey, InvocationKind.ASYNC);


        InvocationExecutionFactory.ExecutionLookup lookup =
                executionFactory.createOrReuseExecution(functionName, spec, request, idempotencyKey, traceId,
                        InvocationKind.ASYNC);

        // The key found an already-archived execution: the mutable record is gone,
        // but the outcome the replay needs is still there. Same handling as the
        // sync coordinator (ReactiveInvocationCoordinator.invoke) - checked before
        // ever touching lookup.executionRecord(), which the factory returns as null
        // for this branch.
        Outcome settled = lookup.settledOutcome();
        if (settled != null) {
            return responseMapper.terminalResponse(lookup.settledExecutionId(), settled);
        }

        // Outcome evicted for capacity: the replay does not re-run the function, it
        // returns 410. As on the sync branch, this must be checked before dereferencing
        // executionRecord.
        if (lookup.gone()) {
            throw new OutcomeGoneException(lookup.settledExecutionId());
        }

        ExecutionRecord executionRecord = lookup.executionRecord();

        // replay is a component that checks if the execution has already completed and returns the appropriate response if so.
        // If replay is not null, it means the execution has already completed, and we can return the terminal response immediately.
        // replay is stored in a variable to avoid calling responseMapper.terminalResponse(record) multiple times, which could be inefficient.
        InvocationResponse replay = responseMapper.terminalResponse(executionRecord);
        if (replay != null) {
            return replay;
        }

        // If the execution is new (not a replay), we need to enqueue it for processing.
        InvocationEnqueueSupport.admitIfNew(lookup,
                () -> InvocationEnqueueSupport.enqueueOrThrow(enqueuer, metrics, executionRecord));
            
        // Return a response indicating that the invocation has been queued for processing.
        // This time, we return a new InvocationResponse with the status "queued" to indicate that the invocation has been accepted 
        // and is waiting to be processed. And not returning the replay response, because we have already checked that the execution 
        // is new and not a replay.
        return new InvocationResponse(executionRecord.executionId(), "queued", null, null);
    }

    public Optional<ExecutionStatus> getStatus(String executionId) {
        ExecutionRecord live = executionStore.getOrNull(executionId);
        if (live != null) {
            return Optional.of(responseMapper.toStatus(live));
        }
        // Finished: the mutable record is gone, the outcome is not.
        Outcome outcome = executionStore.outcomeOf(executionId);
        return outcome == null
                ? Optional.empty()
                : Optional.of(responseMapper.toStatus(executionId, outcome));
    }

    public void dispatch(InvocationTask task) {
        completionHandler.dispatch(task);
    }

    public void completeExecution(String executionId, DispatchResult dispatchResult) {
        completionHandler.completeExecution(executionId, dispatchResult);
    }

    public void completeExecution(String executionId, DispatchResult dispatchResult, Integer completedAttempt) {
        completionHandler.completeExecution(executionId, dispatchResult, completedAttempt);
    }

    public void completeExecution(String executionId, InvocationResult result) {
        completionHandler.completeExecution(executionId, result);
    }

    public void completeExecution(String executionId, InvocationResult result, Integer completedAttempt) {
        completionHandler.completeExecution(executionId, result, completedAttempt);
    }

    /**
     * Under overload most arrivals are refused, and today each one first builds an
     * execution record, files it in the store and claims an idempotency key, only for
     * `abandonAdmission` to undo all three. At the peak of the comparison profile that
     * was 590 refusals a second against 299 dispatches, on a control plane the chart
     * caps at one CPU.
     *
     * <p>Skipped whenever a non-blank idempotency key is present: that request may be
     * a replay whose result is already stored, and a replay must be served however
     * full the queue is. The refusal is conservatively linearized at this pre-check;
     * new executions that pass the pre-check still reach `enqueue`, which remains
     * authoritative.
     */
    private void refuseEarlyIfQueueFull(String functionName, FunctionSpec spec, String idempotencyKey,
                                        InvocationKind kind) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()
                || !reactiveCoordinator.queueFullMeansRefusal(spec)
                || !enqueuer.isQueueFull(functionName)) {
            return;
        }
        metrics.queueRejected(functionName);
        metrics.refused(functionName, kind);
        throw new QueueFullException();
    }

}
