package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchLease;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private final FunctionCapacityRegistry capacityRegistry;

    /**
     * Production constructor: deployment invocations wait for a scaled-to-zero
     * managed deployment before their external dispatch.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ExecutionCompletionHandler(ExecutionStore executionStore,
                                      @Nullable InvocationEnqueuer enqueuer,
                                      DispatcherRouter dispatcherRouter,
                                      Metrics metrics,
                                      DeploymentWakeUpGate wakeUpGate,
                                      FunctionCapacityRegistry capacityRegistry) {
        this.executionStore = executionStore;
        this.enqueuer = enqueuer == null ? InvocationEnqueuer.noOp() : enqueuer;
        this.dispatcherRouter = dispatcherRouter;
        this.metrics = metrics;
        this.wakeUpGate = wakeUpGate;
        // A handler built without a shared registry (a bare unit test) still bounds direct
        // admission: it owns a private registry rather than admitting unbounded work.
        this.capacityRegistry = capacityRegistry == null ? new FunctionCapacityRegistry() : capacityRegistry;
        // The store knows nothing about dispatch slots or shared futures; it only
        // knows a record fell out of inFlight on its own. Closing what that record
        // was actually holding is this class's job.
        this.executionStore.onAdministrativeExpiry(this::handleAdministrativeExpiry);
        // Archiving is the ONLY event common to every terminal policy: normal completion, sync
        // timeout, administrative expiry, offload conclusion, and the queue-side terminations a
        // module owns (queue-wait timeout, function removed while queued) — which this class
        // never sees at all. Hanging the end-to-end conclusion here is what makes "exactly one
        // per invocation" true for all of them instead of only for the paths that pass through
        // publishFinalCompletion; the record's own guard keeps it to one.
        this.executionStore.onTerminal(this::recordTerminalConclusionOnce);
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
                null, null);
    }

    /** Compatibility constructor that also passes a wake-up gate, without a shared registry. */
    public ExecutionCompletionHandler(ExecutionStore executionStore,
                                      @Nullable InvocationEnqueuer enqueuer,
                                      DispatcherRouter dispatcherRouter,
                                      Metrics metrics,
                                      DeploymentWakeUpGate wakeUpGate) {
        this(executionStore, enqueuer, dispatcherRouter, metrics, wakeUpGate, null);
    }

    /**
     * Completion path for offloaded executions: no retry and no dispatch-slot
     * release (offloaded calls never acquired one), just state + metrics + future.
     *
     * <p>Finalization is unconditional for the local attempt's completion (finding R4):
     * an already-terminal record must not authorize an early return that leaves the
     * shared future or the store open. A terminal state is final, so the
     * already-definitive result prevails and this late result records nothing; the
     * future completion and the settle are both idempotent no-ops when a terminal
     * marker already concluded the execution.
     */
    public void completeOffloadedExecution(String executionId, InvocationResult result) {
        ExecutionRecord executionRecord = executionStore.getOrNull(executionId);
        if (executionRecord == null) {
            return;
        }
        boolean concluded;
        synchronized (executionRecord) {
            concluded = !isTerminal(executionRecord.state());
            if (concluded) {
                if (result.success()) {
                    executionRecord.markSuccess(result.output(), result.statusCode(),
                            result.headers(), result.encoding());
                } else {
                    executionRecord.markError(result.error());
                }
            }
        }
        if (concluded) {
            String functionName = executionRecord.task().functionName();
            if (result.success()) {
                metrics.success(functionName);
            } else {
                metrics.error(functionName);
            }
        }
        // Future published outside the record monitor (same invariant as
        // publishFinalCompletion): synchronous waiters must not run under the lock.
        // complete() on an already-done future is a no-op, so a late result cannot
        // overwrite the already-definitive answer.
        executionRecord.completion().complete(result);
        executionStore.settle(executionRecord);
    }

    /**
     * Infrastructure failure of an offloaded call: final by design (no local
     * fallback). The record stores the error; the shared future completes
     * exceptionally so every idempotent waiter surfaces the same 502/504.
     *
     * <p>Finalization is unconditional (finding R4), exactly as in
     * {@link #completeOffloadedExecution}: a terminal record does not make this an
     * early return, and the already-definitive result prevails over the late failure.
     */
    public void failOffloadedExecution(String executionId, OffloadFailedException failure) {
        ExecutionRecord executionRecord = executionStore.getOrNull(executionId);
        if (executionRecord == null) {
            return;
        }
        ErrorInfo error = new ErrorInfo(
                failure.gatewayTimeout() ? OffloadGateway.OFFLOAD_TIMEOUT_CODE : OffloadGateway.OFFLOAD_FAILED_CODE,
                failure.getMessage());
        boolean concluded;
        synchronized (executionRecord) {
            concluded = !isTerminal(executionRecord.state());
            if (concluded) {
                executionRecord.markError(error);
            }
        }
        if (concluded) {
            metrics.error(executionRecord.task().functionName());
        }
        executionRecord.completion().completeExceptionally(failure);
        executionStore.settle(executionRecord);
    }

    public void dispatch(InvocationTask task) {
        dispatchInternal(task, null);
    }

    /**
     * Direct (no-queue) admission: the core applies the configured concurrency itself.
     * This acquires the attempt's capacity lease and dispatches; when the function is at
     * capacity there is no room, so it rejects per the overload contract (a
     * {@link QueueFullException}, which the API surfaces as a 429) rather than dispatching
     * unbounded work. Sync-disabled is not capacity-disabled.
     */
    public void dispatchDirect(InvocationTask task) {
        DispatchLease lease = capacityRegistry.tryAcquireLease(
                task.functionName(), task.functionSpec().concurrency());
        if (lease == null) {
            throw new QueueFullException();
        }
        dispatchInternal(task, lease);
    }

    /**
     * Dispatches with a lease already acquired by the caller (the core-only retry enqueuer).
     * The lease travels with the attempt and is released exactly once by its completion.
     */
    public void dispatchWithLease(InvocationTask task, DispatchLease lease) {
        dispatchInternal(task, lease);
    }

    /**
     * Shared dispatch body. {@code directLease} is the lease the direct path just acquired;
     * it is {@code null} for the queue path, whose scheduler already holds a name-based
     * slot and releases it through the enqueuer. Both register the real cancellable
     * transport handle on the record and apply the attempt's own deadline.
     */
    private void dispatchInternal(InvocationTask task, @Nullable DispatchLease directLease) {
        ExecutionRecord executionRecord = executionStore.getOrNull(task.executionId());
        if (executionRecord == null) {
            if (directLease != null) {
                directLease.release();
            } else {
                releaseDispatchSlot(task.functionName());
            }
            return;
        }

        boolean terminal;
        synchronized (executionRecord) {
            terminal = executionRecord.isTerminal();
            if (!terminal) {
                if (directLease != null) {
                    executionRecord.attachDispatchLease(directLease);
                }
                executionRecord.markRunning();
                executionRecord.markDispatchedAt();
            }
        }
        if (terminal) {
            if (directLease != null) {
                directLease.release();
            } else {
                releaseDispatchSlotOnce(executionRecord, task.attempt(), task.functionName());
            }
            return;
        }
        metrics.dispatch(task.functionName());

        ExecutionMode mode = task.functionSpec().executionMode();
        int attemptAtDispatch = task.attempt();
        CompletableFuture<DispatchResult> future;
        try {
            future = switch (mode) {
                case LOCAL -> dispatcherRouter.dispatchLocal(task);
                case EXTERNAL -> dispatcherRouter.dispatchExternal(task);
                case DEPLOYMENT -> dispatchDeployment(task);
            };
        } catch (Exception ex) {
            // The dispatcher threw synchronously: completeExecution releases the lease (direct)
            // or the name-based slot (queue) that was already recorded on the record.
            completeExecution(task.executionId(),
                    DispatchResult.warm(InvocationResult.error(mode.name() + "_ERROR", ex.getMessage())),
                    attemptAtDispatch);
            return;
        }

        // Publish the real cancellable handle before the completion callback. An
        // administrative expiry that wins this race can still cancel the underlying
        // transport subscriber rather than leaving the work (and its payload) alive after
        // the capacity was released. For DEPLOYMENT this is the composed wake-up future.
        executionRecord.attachDispatchHandle(future);

        // The raw work's own completion is what releases a direct attempt's lease: a
        // non-interruptible LOCAL handler keeps its capacity until the raw future actually
        // ends, so a retry cannot acquire a fresh slot while the old handler still runs
        // (invariant I4 / acceptance "no path bypasses the cap").
        if (directLease != null) {
            future.whenComplete((ignoredResult, ignoredError) -> directLease.release());
        }

        // The attempt's own deadline (ADR 0001 §3.4, row 9) is a separate clock from the raw
        // transport. It is applied to a mirror of the raw future, never to the raw future
        // itself, so when the deadline fires the raw future stays pending (and the direct
        // lease stays held) for work that has not actually ended.
        long attemptDeadlineMs = task.functionSpec().timeoutMs();
        CompletableFuture<DispatchResult> attempt;
        if (attemptDeadlineMs > 0) {
            attempt = new CompletableFuture<>();
            future.whenComplete((result, error) -> {
                if (error != null) {
                    attempt.completeExceptionally(error);
                } else {
                    attempt.complete(result);
                }
            });
            attempt.orTimeout(attemptDeadlineMs, TimeUnit.MILLISECONDS);
        } else {
            attempt = future;
        }

        attempt.whenComplete((dispatchResult, error) -> {
            if (error instanceof TimeoutException) {
                // Attempt deadline elapsed: conclude the wait and feed the retry policy, but do
                // not release the direct lease here. The raw future's own completion (registered
                // above) releases it, so a non-interruptible LOCAL handler keeps its slot until
                // its work actually ends and the retry cannot acquire a fresh slot meanwhile.
                if (directLease != null) {
                    executionRecord.takeDispatchLease();
                }
                completeExecution(task.executionId(),
                        DispatchResult.warm(InvocationResult.error("ATTEMPT_TIMEOUT", "Attempt deadline exceeded")),
                        attemptAtDispatch);
            } else if (error != null) {
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
        synchronized (executionRecord) {
            completion = completeUnderLock(executionRecord, dispatchResult, completedAttempt);
        }
        publishFinalCompletion(executionRecord, completion);
        // Last, and outside the monitor: completeUnderLock releases the dispatch slot
        // even when it finds the record already terminal, and archiving it first would
        // make it unreachable at exactly that step - the slot would stay taken forever.
        // settle() ignores non-terminal records, so the retry branch is left intact.
        //
        // There is no "answer the shared future with the late result" branch here any
        // more (ADR 0001 §5, invariant I1): every terminal marker concludes the shared
        // future itself, so a late dispatch result on an already-terminal record must
        // not overwrite the already-definitive answer - the future is already done and
        // the state stays terminal.
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
        releaseAttemptCapacity(executionRecord, attempt, functionName);
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

        if (result.success()) {
            executionRecord.markSuccess(result.output(), result.statusCode(),
                    result.headers(), result.encoding());
        } else {
            executionRecord.markError(result.error());
        }
        if (dispatchResult.coldStart()) {
            executionRecord.markColdStart(dispatchResult.initDurationMs() != null ? dispatchResult.initDurationMs() : 0);
        }

        // Durations are monotonic, not wall-clock diffs: an NTP step between admission and
        // completion must not fabricate a latency sample. Service time is this attempt's
        // dispatch-to-completion, wait is this attempt's enqueue-to-dispatch, and the total is
        // the ORIGINAL admission to completion — retries and their waits included.
        Long finishedAtNanos = executionRecord.finishedAtNanos();
        Long startedAtNanos = executionRecord.startedAtNanos();
        Long latencyMs = (startedAtNanos != null && finishedAtNanos != null)
                ? nanosToMs(startedAtNanos, finishedAtNanos)
                : null;
        Long queueWaitMs = startedAtNanos == null
                ? null
                : nanosToMs(executionRecord.attemptEnqueuedAtNanos(), startedAtNanos);
        Long e2eMs = finishedAtNanos == null
                ? null
                : nanosToMs(executionRecord.admittedAtNanos(), finishedAtNanos);
        return new FinalCompletion(functionName, result, latencyMs, queueWaitMs, e2eMs,
                dispatchResult.coldStart(), dispatchResult.initDurationMs(), false);
    }

    private static Long nanosToMs(long startNanos, long endNanos) {
        return TimeUnit.NANOSECONDS.toMillis(endNanos - startNanos);
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
                // The record's own clock, not Instant.now(): under a steered clock a retry task
                // stamped from the system clock is the one inconsistent timestamp in the record.
                executionRecord.now(),
                currentTask.attempt() + 1,
                currentTask.kind()
        );
        executionRecord.resetForRetry(retryTask);
        try {
            InvocationEnqueueSupport.enqueueOrThrow(enqueuer, metrics, executionRecord);
            return null;
        } catch (RuntimeException ex) {
            // QueueFullException is the expected refusal; anything else is belt-and-braces.
            // enqueueOrThrow only throws QueueFullException on its own account, but the enqueuer
            // it wraps is pluggable (queue-backed, sync-queue, executor-backed, or a future
            // implementation) and scheduling a retry is exactly the kind of call whose failure
            // must never leave the record parked in QUEUED with nothing left to complete it.
            // Both get the same terminal treatment; only the log line differs.
            if (ex instanceof QueueFullException) {
                log.warn("Retry queue full for execution {}, completing with error", executionRecord.executionId());
            } else {
                log.warn("Retry scheduling failed for execution {}, completing with error: {}",
                        executionRecord.executionId(), ex.toString());
            }
            return retryExhaustedUnderLock(executionRecord, functionName, result);
        }
    }

    /**
     * The retry could not be scheduled: the invocation concludes in error here. The attempt
     * never dispatched, so there is no service time and no wait for it — but the invocation's
     * total, admission to this conclusion, is still real and is what the e2e timer records.
     */
    private static FinalCompletion retryExhaustedUnderLock(ExecutionRecord executionRecord,
                                                           String functionName,
                                                           InvocationResult result) {
        executionRecord.markError(result.error());
        Long e2eMs = nanosToMs(executionRecord.admittedAtNanos(), executionRecord.finishedAtNanos());
        return FinalCompletion.retryExhausted(functionName, result, e2eMs);
    }

    private void publishFinalCompletion(ExecutionRecord executionRecord, FinalCompletion completion) {
        if (completion == null) {
            return;
        }
        String functionName = completion.functionName();
        // The guard is consumed BEFORE recording, not after: an administrative expiry running on
        // Caffeine's removal executor can win it between completeUnderLock releasing the record
        // monitor and this line. Claiming it first is what makes the end-to-end conclusion single;
        // recording first and claiming after left a window for two samples.
        boolean wonTheConclusion = executionRecord.markMetricsRecorded();
        recordCompletionMetrics(completion, wonTheConclusion);
        if (completion.result().success()) {
            metrics.success(functionName);
        } else {
            metrics.error(functionName);
        }
        executionRecord.completion().complete(completion.result());
    }

    private void recordCompletionMetrics(FinalCompletion completion, boolean wonTheConclusion) {
        String functionName = completion.functionName();
        Metrics.FunctionTimers timers = metrics.timers(functionName);
        // Cold/warm is a property of a dispatch that actually ran. A retry that never scheduled
        // (queue full) dispatched nothing, so it is neither a cold nor a warm start.
        if (!completion.retryExhausted()) {
            if (completion.coldStart()) {
                metrics.coldStart(functionName);
                if (completion.initDurationMs() != null) {
                    timers.initDuration().record(completion.initDurationMs(), TimeUnit.MILLISECONDS);
                }
            } else {
                metrics.warmStart(functionName);
            }
        }
        if (completion.latencyMs() != null) {
            timers.latency().record(completion.latencyMs(), TimeUnit.MILLISECONDS);
        }
        if (completion.queueWaitMs() != null && completion.queueWaitMs() >= 0) {
            timers.queueWait().record(completion.queueWaitMs(), TimeUnit.MILLISECONDS);
        }
        if (wonTheConclusion && completion.e2eMs() != null && completion.e2eMs() >= 0) {
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
        static FinalCompletion retryExhausted(String functionName, InvocationResult result, Long e2eMs) {
            return new FinalCompletion(functionName, result, null, null, e2eMs, false, null, true);
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
        Future<?> handle;
        synchronized (executionRecord) {
            wasNonTerminal = !isTerminal(executionRecord.state());
            if (wasNonTerminal) {
                executionRecord.markError(new ErrorInfo(EXECUTION_EXPIRED_CODE,
                        "Execution exceeded its maximum lifetime before a dispatch outcome arrived"));
            }
            result = resultFromRecord(executionRecord);
            handle = executionRecord.takeDispatchHandle();
        }
        InvocationTask task = executionRecord.task();
        // Give the attempt's capacity back exactly once (lease for a direct admission, the
        // name-based slot for a queue dispatch, nothing for work that never dispatched).
        releaseAttemptCapacity(executionRecord, task.attempt(), task.functionName());
        // Then request local cancellation of the real transport handle so the work (and its
        // payload) does not outlive the released admission. This cancels the raw HTTP
        // subscriber; it does not - and cannot - promise that a remote function stops.
        if (handle != null) {
            handle.cancel(true);
        }
        if (wasNonTerminal) {
            metrics.error(task.functionName());
        }
        // An invocation that died for maxLifetime still has one end-to-end conclusion: its total,
        // admission to expiry. The store's terminal listener records it at the settle below,
        // guarded against a late dispatch racing this eviction.
        executionRecord.completion().complete(result);
        executionStore.settle(executionRecord);
    }

    /**
     * The single end-to-end conclusion for an invocation that became terminal outside the normal
     * completion path — a sync caller's timeout, an administrative expiry, or an offloaded call
     * concluded by the remote plane — where the service time was censored (no dispatch on this
     * side produced an observed completion). Only the total duration is recorded, and only once:
     * the record's guard makes a late dispatch callback racing the timeout or the eviction a no-op
     * the second time.
     *
     * <p>The measurability check comes BEFORE the guard is claimed. Claiming first would burn the
     * invocation's one conclusion on a record that has no {@code finishedAtNanos} to record, and
     * the real terminal path arriving afterwards would then find the guard spent and stay silent.
     */
    private void recordTerminalConclusionOnce(ExecutionRecord executionRecord) {
        Long finishedAtNanos = executionRecord.finishedAtNanos();
        if (finishedAtNanos == null) {
            return;
        }
        if (!executionRecord.markMetricsRecorded()) {
            return;
        }
        Long e2eMs = nanosToMs(executionRecord.admittedAtNanos(), finishedAtNanos);
        if (e2eMs >= 0) {
            metrics.timers(executionRecord.task().functionName())
                    .e2eLatency().record(e2eMs, TimeUnit.MILLISECONDS);
        }
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

    /**
     * Releases exactly what this attempt acquired (invariant I4). A direct attempt releases
     * the lease it holds; a queue-dispatched attempt releases the name-based slot its
     * scheduler acquired; an attempt that never dispatched releases nothing - which is what
     * closes review finding R5 (a direct completion could steal a queued dispatch's slot).
     * Its accessors are individually synchronized, so it is safe both under the record
     * monitor (the completion path) and outside it (administrative expiry).
     */
    private void releaseAttemptCapacity(ExecutionRecord executionRecord, int attempt, String functionName) {
        if (executionRecord.wasDirectAdmission()) {
            DispatchLease lease = executionRecord.takeDispatchLease();
            if (lease != null) {
                lease.release();
            }
        } else if (executionRecord.wasDispatched()) {
            releaseDispatchSlotOnce(executionRecord, attempt, functionName);
        }
    }

    private static boolean isTerminal(it.unimib.datai.nanofaas.controlplane.execution.ExecutionState state) {
        return state == it.unimib.datai.nanofaas.controlplane.execution.ExecutionState.SUCCESS
                || state == it.unimib.datai.nanofaas.controlplane.execution.ExecutionState.ERROR
                || state == it.unimib.datai.nanofaas.controlplane.execution.ExecutionState.TIMEOUT;
    }
}
