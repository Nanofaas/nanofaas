package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.common.model.ErrorInfo;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadFailedException;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadGateway;
import it.unimib.datai.nanofaas.controlplane.queue.QueueFullException;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Handles dispatch to execution runtimes and post-dispatch completion (retry, metrics, state transitions).
 *
 * <p>This is a collaborator of {@link InvocationService}: InvocationService owns the entry-point
 * API (invokeSyncReactive / invokeAsync / getStatus) while this class owns the dispatch and completion
 * lifecycle.</p>
 */
@Service
public class ExecutionCompletionHandler {
    private static final Logger log = LoggerFactory.getLogger(ExecutionCompletionHandler.class);

    /**
     * Marks an outcome the administrative-expiry path fabricated because the real
     * one never arrived, as opposed to a genuine runtime error.
     */
    static final String EXECUTION_EXPIRED_CODE = "EXECUTION_EXPIRED";

    private final ExecutionStore executionStore;
    private final InvocationEnqueuer enqueuer;
    private final DispatcherRouter dispatcherRouter;
    private final Metrics metrics;
    @Nullable private final DeploymentWakeUpGate wakeUpGate;

    /**
     * Production constructor: deployment invocations wait for a scaled-to-zero
     * managed deployment before their external dispatch.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ExecutionCompletionHandler(ExecutionStore executionStore,
                                      @Nullable InvocationEnqueuer enqueuer,
                                      DispatcherRouter dispatcherRouter,
                                      Metrics metrics,
                                      DeploymentWakeUpGate wakeUpGate) {
        this.executionStore = executionStore;
        this.enqueuer = enqueuer == null ? InvocationEnqueuer.noOp() : enqueuer;
        this.dispatcherRouter = dispatcherRouter;
        this.metrics = metrics;
        this.wakeUpGate = wakeUpGate;
        // The store knows nothing about dispatch slots or shared futures; it only
        // knows a record fell out of inFlight on its own. Closing what that record
        // was actually holding is this class's job.
        this.executionStore.onAdministrativeExpiry(this::handleAdministrativeExpiry);
    }

    /**
     * Compatibility constructor for direct unit-test construction. Production
     * uses the Spring constructor above.
     */
    public ExecutionCompletionHandler(ExecutionStore executionStore,
                                      @Nullable InvocationEnqueuer enqueuer,
                                      DispatcherRouter dispatcherRouter,
                                      Metrics metrics) {
        this(executionStore, enqueuer, dispatcherRouter, metrics,
                null);
    }

    /**
     * Completion path for offloaded executions: no retry and no dispatch-slot
     * release (offloaded calls never acquired one), just state + metrics + future.
     */
    public void completeOffloadedExecution(String executionId, InvocationResult result) {
        ExecutionRecord executionRecord = executionStore.getOrNull(executionId);
        if (executionRecord == null) {
            return;
        }
        synchronized (executionRecord) {
            if (isTerminal(executionRecord.state())) {
                return;
            }
            if (result.success()) {
                executionRecord.markSuccess(result.output(), result.statusCode(),
                        result.headers(), result.encoding());
            } else {
                executionRecord.markError(result.error());
            }
        }
        String functionName = executionRecord.task().functionName();
        if (result.success()) {
            metrics.success(functionName);
        } else {
            metrics.error(functionName);
        }
        // Future published outside the record monitor (same invariant as
        // publishFinalCompletion): synchronous waiters must not run under the lock.
        executionRecord.completion().complete(result);
        executionStore.settle(executionRecord);
    }

    /**
     * Infrastructure failure of an offloaded call: final by design (no local
     * fallback). The record stores the error; the shared future completes
     * exceptionally so every idempotent waiter surfaces the same 502/504.
     */
    public void failOffloadedExecution(String executionId, OffloadFailedException failure) {
        ExecutionRecord executionRecord = executionStore.getOrNull(executionId);
        if (executionRecord == null) {
            return;
        }
        ErrorInfo error = new ErrorInfo(
                failure.gatewayTimeout() ? OffloadGateway.OFFLOAD_TIMEOUT_CODE : OffloadGateway.OFFLOAD_FAILED_CODE,
                failure.getMessage());
        synchronized (executionRecord) {
            if (isTerminal(executionRecord.state())) {
                return;
            }
            executionRecord.markError(error);
        }
        metrics.error(executionRecord.task().functionName());
        executionRecord.completion().completeExceptionally(failure);
        executionStore.settle(executionRecord);
    }

    public void dispatch(InvocationTask task) {
        ExecutionRecord executionRecord = executionStore.getOrNull(task.executionId());
        if (executionRecord == null) {
            releaseDispatchSlot(task.functionName());
            return;
        }

        boolean terminal;
        synchronized (executionRecord) {
            terminal = executionRecord.isTerminal();
            if (!terminal) {
                executionRecord.markRunning();
                executionRecord.markDispatchedAt();
            }
        }
        if (terminal) {
            releaseDispatchSlotOnce(executionRecord, task.attempt(), task.functionName());
            return;
        }
        metrics.dispatch(task.functionName());

        ExecutionMode mode = task.functionSpec().executionMode();
        int attemptAtDispatch = task.attempt();
        java.util.concurrent.CompletableFuture<DispatchResult> future;
        try {
            future = switch (mode) {
                case LOCAL -> dispatcherRouter.dispatchLocal(task);
                case EXTERNAL -> dispatcherRouter.dispatchExternal(task);
                case DEPLOYMENT -> dispatchDeployment(task);
            };
        } catch (Exception ex) {
            completeExecution(task.executionId(),
                    DispatchResult.warm(InvocationResult.error(mode.name() + "_ERROR", ex.getMessage())),
                    attemptAtDispatch);
            return;
        }

        future.whenComplete((dispatchResult, error) -> {
            if (error != null) {
                Throwable failure = deploymentWakeUpFailure(error);
                completeExecution(task.executionId(),
                        DispatchResult.warm(InvocationResult.error(
                                failure != null ? "DEPLOYMENT_WAKE_UP_FAILED" : mode.name() + "_ERROR",
                                (failure != null ? failure : error).getMessage())),
                        attemptAtDispatch);
            } else {
                completeExecution(task.executionId(), dispatchResult, attemptAtDispatch);
            }
        });
    }

    private CompletableFuture<DispatchResult> dispatchDeployment(InvocationTask task) {
        try {
            if (wakeUpGate == null) {
                return dispatcherRouter.dispatchExternal(task);
            }
            return wakeUpGate.ensureReady(task)
                    .handle((ignored, error) -> {
                        if (error != null) {
                            throw new DeploymentWakeUpException(error);
                        }
                        return ignored;
                    })
                    .thenCompose(ignored -> dispatcherRouter.dispatchExternal(task));
        } catch (Exception error) {
            return CompletableFuture.failedFuture(new DeploymentWakeUpException(error));
        }
    }

    private static Throwable deploymentWakeUpFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof DeploymentWakeUpException) {
                return current.getCause() != null ? current.getCause() : current;
            }
        }
        return null;
    }

    private static final class DeploymentWakeUpException extends RuntimeException {
        private DeploymentWakeUpException(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }

    public void completeExecution(String executionId, DispatchResult dispatchResult) {
        ExecutionRecord executionRecord = executionStore.getOrNull(executionId);
        if (executionRecord == null) {
            return;
        }

        completeExecution(executionRecord, dispatchResult, null);
    }

    public void completeExecution(String executionId, DispatchResult dispatchResult, Integer completedAttempt) {
        ExecutionRecord executionRecord = executionStore.getOrNull(executionId);
        if (executionRecord == null) {
            return;
        }

        completeExecution(executionRecord, dispatchResult, completedAttempt);
    }

    // S2445: the record IS the per-execution lock object; a dedicated monitor would serialize all executions.
    @SuppressWarnings("java:S2445")
    private void completeExecution(ExecutionRecord executionRecord, DispatchResult dispatchResult, Integer completedAttempt) {
        FinalCompletion completion;
        boolean lateResultForTerminalRecord;
        synchronized (executionRecord) {
            completion = completeUnderLock(executionRecord, dispatchResult, completedAttempt);
            // Il record era gia' terminale e questo risultato riguarda comunque il
            // tentativo in corso: non c'e' nessuna FinalCompletion da pubblicare, ma
            // la future condivisa e' ancora pendente e qualcuno potrebbe starci sopra.
            // Il ramo di retry non entra qui - resetForRetry riporta a QUEUED - e un
            // risultato arrivato per un tentativo vecchio nemmeno.
            lateResultForTerminalRecord = completion == null
                    && executionRecord.isTerminal()
                    && (completedAttempt == null || executionRecord.task().attempt() == completedAttempt);
        }
        publishFinalCompletion(executionRecord, completion);
        if (lateResultForTerminalRecord) {
            // Un timeout sincrono ha reso terminale il record mentre il dispatch era
            // in volo. Lo stato registrato resta TIMEOUT - e' un invariante voluto e
            // gia' coperto da un test - ma chi e' ancora in attesa sulla future
            // condivisa, tipicamente un secondo chiamante con la stessa chiave di
            // idempotenza che ha un budget suo, deve ricevere la risposta vera invece
            // di aspettare invano fino al proprio timeout. complete() su una future
            // gia' completata non fa nulla, quindi non puo' sovrascrivere niente.
            executionRecord.completion().complete(dispatchResult.result());
        }
        // Ultimo, e fuori dal monitor: completeUnderLock rilascia lo slot di dispatch
        // anche quando trova il record gia' terminale (un timeout sincrono che ha
        // marcato il record mentre il dispatch era ancora in volo), e archiviarlo
        // prima lo renderebbe irreperibile proprio a quel passaggio - lo slot
        // resterebbe preso per sempre. settle() ignora i record non terminali,
        // quindi il ramo di retry resta intatto.
        executionStore.settle(executionRecord);
    }

    /**
     * State transitions only; final-completion meter recording and future completion
     * happen outside the record monitor (see publishFinalCompletion) so synchronous
     * whenComplete callbacks never run while the lock is held. (Retry-path counters
     * still increment under the lock.)
     */
    private FinalCompletion completeUnderLock(ExecutionRecord executionRecord,
                                              DispatchResult dispatchResult,
                                              Integer completedAttempt) {
        InvocationResult result = dispatchResult.result();
        InvocationTask currentTask = executionRecord.task();
        int attempt = completedAttempt != null ? completedAttempt : currentTask.attempt();
        if (completedAttempt != null && currentTask.attempt() != completedAttempt) {
            return null;
        }

        String functionName = currentTask.functionName();
        releaseDispatchSlotOnce(executionRecord, attempt, functionName);
        if (isTerminal(executionRecord.state())) {
            return null;
        }

        boolean shouldRetry = !result.success()
                && currentTask.attempt() <= currentTask.functionSpec().maxRetries();
        if (shouldRetry) {
            // handleRetry always terminates the retry path: null when the retry was
            // enqueued (never fall through to final completion), a FinalCompletion
            // when retry is exhausted (queue full).
            return handleRetry(executionRecord, currentTask, result);
        }

        Instant enqueuedAt = currentTask.enqueuedAt();
        Instant startedAt = executionRecord.startedAt();
        if (result.success()) {
            executionRecord.markSuccess(result.output(), result.statusCode(),
                    result.headers(), result.encoding());
        } else {
            executionRecord.markError(result.error());
        }
        Instant finishedAt = executionRecord.finishedAt();
        if (dispatchResult.coldStart()) {
            executionRecord.markColdStart(dispatchResult.initDurationMs() != null ? dispatchResult.initDurationMs() : 0);
        }

        Long latencyMs = elapsedMs(startedAt, finishedAt);
        Long queueWaitMs = elapsedMs(enqueuedAt, startedAt);
        Long e2eMs = elapsedMs(enqueuedAt, finishedAt);
        return new FinalCompletion(functionName, result, latencyMs, queueWaitMs, e2eMs,
                dispatchResult.coldStart(), dispatchResult.initDurationMs(), false);
    }

    private static Long elapsedMs(Instant start, Instant end) {
        return (start != null && end != null) ? end.toEpochMilli() - start.toEpochMilli() : null;
    }

    /**
     * Returns a final completion when the retry path terminates (queue full), null when the
     * retry was enqueued or when no retry applies (success or attempts exhausted).
     */
    private FinalCompletion handleRetry(ExecutionRecord executionRecord, InvocationTask currentTask, InvocationResult result) {
        if (result.success() || currentTask.attempt() > currentTask.functionSpec().maxRetries()) {
            return null;
        }
        String functionName = currentTask.functionName();
        metrics.retry(functionName);
        InvocationTask retryTask = new InvocationTask(
                executionRecord.executionId(),
                functionName,
                currentTask.functionSpec(),
                currentTask.request(),
                null,  // No idempotency key for retry - retry is internal
                currentTask.traceId(),
                Instant.now(),
                currentTask.attempt() + 1,
                currentTask.kind()
        );
        executionRecord.resetForRetry(retryTask);
        try {
            InvocationEnqueueSupport.enqueueOrThrow(enqueuer, metrics, executionRecord);
            return null;
        } catch (QueueFullException _) {
            log.warn("Retry queue full for execution {}, completing with error", executionRecord.executionId());
            executionRecord.markError(result.error());
            return FinalCompletion.retryExhausted(functionName, result);
        } catch (RuntimeException ex) {
            // Belt-and-braces: enqueueOrThrow only ever throws QueueFullException on its
            // own account, but the enqueuer it wraps is pluggable (queue-backed, sync-queue,
            // executor-backed, or a future implementation) and scheduling a retry is exactly
            // the kind of call whose failure must never leave the record parked in QUEUED
            // with nothing left that will ever complete it. Any other exception surfacing
            // from the scheduling attempt gets the same terminal treatment as a full queue.
            log.warn("Retry scheduling failed for execution {}, completing with error: {}",
                    executionRecord.executionId(), ex.toString());
            executionRecord.markError(result.error());
            return FinalCompletion.retryExhausted(functionName, result);
        }
    }

    private void publishFinalCompletion(ExecutionRecord executionRecord, FinalCompletion completion) {
        if (completion == null) {
            return;
        }
        String functionName = completion.functionName();
        if (!completion.retryExhausted()) {
            recordCompletionMetrics(completion);
        }
        if (completion.result().success()) {
            metrics.success(functionName);
        } else {
            metrics.error(functionName);
        }
        executionRecord.completion().complete(completion.result());
    }

    private void recordCompletionMetrics(FinalCompletion completion) {
        String functionName = completion.functionName();
        Metrics.FunctionTimers timers = metrics.timers(functionName);
        if (completion.coldStart()) {
            metrics.coldStart(functionName);
            if (completion.initDurationMs() != null) {
                timers.initDuration().record(completion.initDurationMs(), TimeUnit.MILLISECONDS);
            }
        } else {
            metrics.warmStart(functionName);
        }
        if (completion.latencyMs() != null) {
            timers.latency().record(completion.latencyMs(), TimeUnit.MILLISECONDS);
        }
        if (completion.queueWaitMs() != null && completion.queueWaitMs() >= 0) {
            timers.queueWait().record(completion.queueWaitMs(), TimeUnit.MILLISECONDS);
        }
        if (completion.e2eMs() != null && completion.e2eMs() >= 0) {
            timers.e2eLatency().record(completion.e2eMs(), TimeUnit.MILLISECONDS);
        }
    }

    private record FinalCompletion(String functionName,
                                   InvocationResult result,
                                   Long latencyMs,
                                   Long queueWaitMs,
                                   Long e2eMs,
                                   boolean coldStart,
                                   Long initDurationMs,
                                   boolean retryExhausted) {
        static FinalCompletion retryExhausted(String functionName, InvocationResult result) {
            return new FinalCompletion(functionName, result, null, null, null, false, null, true);
        }
    }

    /**
     * Overload for backward compatibility (e.g., callback completions without cold start info).
     */
    public void completeExecution(String executionId, InvocationResult result) {
        completeExecution(executionId, DispatchResult.warm(result));
    }

    public void completeExecution(String executionId, InvocationResult result, Integer completedAttempt) {
        completeExecution(executionId, DispatchResult.warm(result), completedAttempt);
    }

    /**
     * The store's only escape hatch for a record nobody ever settled: it fell out
     * of {@code inFlight} because {@code maxLifetime} elapsed, not because a
     * dispatch outcome (or a sync timeout) ever touched it. Three things a lost
     * callback would otherwise never do: conclude whoever is still parked on the
     * shared future, give back the dispatch slot the (possibly still-running,
     * possibly long-dead) local attempt is holding, and archive an outcome so
     * {@code GET /v1/executions/{id}} has something to say instead of 404ing
     * forever.
     *
     * <p>Never overwrites a real result: if the record is already terminal
     * (e.g. a sync caller's own timeout already marked it, while the real
     * dispatch outcome still hasn't arrived) its recorded state/output/error is
     * reused verbatim - this only fabricates an error when nothing else ever
     * will. {@link CompletableFuture#complete} is a no-op on an already-done
     * future, so a real completion racing this one always wins, whichever runs
     * first.
     *
     * <p>Slots are a local-dispatch bookkeeping device, not proof the remote
     * runtime stopped executing: releasing one here only means this control
     * plane stops counting the attempt against its own concurrency budget, the
     * same limit an ordinary completion releases. Whether the invoked process is
     * still running past this point is outside what a slot - or this method -
     * can promise.
     *
     * <p>Only released when {@code dispatchedAt} is set - i.e. {@link #dispatch}
     * actually ran for the current attempt. A record still sitting in a queue
     * (task expired before ever being picked up) or served through offload
     * (which never calls {@link #dispatch}) never acquired a slot in the first
     * place, so there is nothing here to give back.
     */
    private void handleAdministrativeExpiry(ExecutionRecord executionRecord) {
        InvocationResult result;
        boolean wasNonTerminal;
        boolean dispatchedLocally;
        synchronized (executionRecord) {
            dispatchedLocally = executionRecord.snapshot().dispatchedAt() != null;
            wasNonTerminal = !isTerminal(executionRecord.state());
            if (wasNonTerminal) {
                executionRecord.markError(new ErrorInfo(EXECUTION_EXPIRED_CODE,
                        "Execution exceeded its maximum lifetime before a dispatch outcome arrived"));
            }
            result = resultFromRecord(executionRecord);
        }
        InvocationTask task = executionRecord.task();
        if (dispatchedLocally) {
            releaseDispatchSlotOnce(executionRecord, task.attempt(), task.functionName());
        }
        if (wasNonTerminal) {
            metrics.error(task.functionName());
        }
        executionRecord.completion().complete(result);
        executionStore.settle(executionRecord);
    }

    /** Called under the record's monitor: reads snapshot() fields directly. */
    private static InvocationResult resultFromRecord(ExecutionRecord executionRecord) {
        ExecutionRecord.Snapshot snapshot = executionRecord.snapshot();
        if (snapshot.state() == it.unimib.datai.nanofaas.controlplane.execution.ExecutionState.SUCCESS) {
            return InvocationResult.successWithEnvelope(snapshot.output(), snapshot.statusCode(),
                    snapshot.headers(), snapshot.encoding());
        }
        ErrorInfo error = snapshot.lastError() != null
                ? snapshot.lastError()
                : new ErrorInfo(EXECUTION_EXPIRED_CODE,
                        "Execution exceeded its maximum lifetime before a dispatch outcome arrived");
        return new InvocationResult(false, null, error);
    }

    private void releaseDispatchSlot(String functionName) {
        enqueuer.releaseDispatchSlot(functionName);
    }

    private void releaseDispatchSlotOnce(ExecutionRecord executionRecord, int attempt, String functionName) {
        if (executionRecord.markDispatchSlotReleased(attempt)) {
            releaseDispatchSlot(functionName);
        }
    }

    private static boolean isTerminal(it.unimib.datai.nanofaas.controlplane.execution.ExecutionState state) {
        return state == it.unimib.datai.nanofaas.controlplane.execution.ExecutionState.SUCCESS
                || state == it.unimib.datai.nanofaas.controlplane.execution.ExecutionState.ERROR
                || state == it.unimib.datai.nanofaas.controlplane.execution.ExecutionState.TIMEOUT;
    }
}
