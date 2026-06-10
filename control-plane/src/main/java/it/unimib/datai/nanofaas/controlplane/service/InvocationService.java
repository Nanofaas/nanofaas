package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionStatus;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionNotFoundException;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Service
public class InvocationService {

    private final FunctionService functionService;
    private final InvocationEnqueuer enqueuer;
    private final ExecutionStore executionStore;
    private final RateLimiter rateLimiter;
    private final Metrics metrics;
    private final ExecutionCompletionHandler completionHandler;
    private final InvocationExecutionFactory executionFactory;
    private final InvocationResponseMapper responseMapper;
    private final ReactiveInvocationCoordinator reactiveCoordinator;

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
                completionHandler,
                new InvocationExecutionFactory(executionStore, idempotencyStore),
                new InvocationResponseMapper(),
                new ReactiveInvocationCoordinator(enqueuer, metrics, syncQueueGateway, completionHandler, new InvocationResponseMapper())
        );
    }

    @Autowired
    public InvocationService(FunctionService functionService,
                             @Nullable InvocationEnqueuer enqueuer,
                             ExecutionStore executionStore,
                             RateLimiter rateLimiter,
                             Metrics metrics,
                             ExecutionCompletionHandler completionHandler,
                             InvocationExecutionFactory executionFactory,
                             InvocationResponseMapper responseMapper,
                             ReactiveInvocationCoordinator reactiveCoordinator) {
        this.functionService = functionService;
        this.enqueuer = enqueuer == null ? InvocationEnqueuer.noOp() : enqueuer;
        this.executionStore = executionStore;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.completionHandler = completionHandler;
        this.executionFactory = executionFactory;
        this.responseMapper = responseMapper;
        this.reactiveCoordinator = reactiveCoordinator;
    }

    public Mono<InvocationResponse> invokeSyncReactive(String functionName,
                                                        InvocationRequest request,
                                                        String idempotencyKey,
                                                        String traceId,
                                                        Integer timeoutOverrideMs) {
        record Prepared(FunctionSpec spec, InvocationExecutionFactory.ExecutionLookup lookup) {}
        return Mono.fromCallable(() -> {
                    enforceRateLimit();
                    FunctionSpec spec = functionService.get(functionName).orElseThrow(FunctionNotFoundException::new);
                    // createOrReuseExecution may spin briefly on contended idempotency
                    // claims; it must never run on the Netty event loop.
                    return new Prepared(spec,
                            executionFactory.createOrReuseExecution(functionName, spec, request, idempotencyKey, traceId));
                })
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMap(prepared -> reactiveCoordinator.invoke(prepared.lookup(), prepared.spec(), timeoutOverrideMs));
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
        InvocationEnqueueSupport.admitIfNew(lookup,
                () -> InvocationEnqueueSupport.enqueueOrThrow(enqueuer, metrics, record));
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

    public void completeExecution(String executionId, DispatchResult dispatchResult, Integer completedAttempt) {
        completionHandler.completeExecution(executionId, dispatchResult, completedAttempt);
    }

    public void completeExecution(String executionId, InvocationResult result) {
        completionHandler.completeExecution(executionId, result);
    }

    public void completeExecution(String executionId, InvocationResult result, Integer completedAttempt) {
        completionHandler.completeExecution(executionId, result, completedAttempt);
    }

    private void enforceRateLimit() {
        if (!rateLimiter.allow()) {
            throw new RateLimitException();
        }
    }

}
