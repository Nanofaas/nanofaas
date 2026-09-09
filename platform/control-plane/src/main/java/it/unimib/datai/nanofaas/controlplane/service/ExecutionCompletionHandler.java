package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchLease;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchAttempt;
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
        executionStore.settle(executionRecord);
        if (concluded) {
            bestEffort(() -> {
                if (result.success()) metrics.success(executionRecord.task().functionName());
                else metrics.error(executionRecord.task().functionName());
            });
        }
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
                executionRecord.markFailure(error, failure);
            }
        }
        executionStore.settle(executionRecord);
        if (concluded) bestEffort(() -> metrics.error(executionRecord.task().functionName()));
    }

    public void dispatch(InvocationTask task) {
        dispatchInternal(task, task.dispatchLease());
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
     * Shared body for direct, retry and queue dispatch. All production schedulers pass
     * their acquired lease with the task. A null lease supports legacy in-process callers.
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
                executionRecord.transportOwnsCapacity();
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
            executionStore.settle(executionRecord);
            return;
        }
        bestEffort(() -> metrics.dispatch(task.functionName()));

        ExecutionMode mode = task.functionSpec().executionMode();
        DispatchAttempt ownership = new DispatchAttempt(task.executionId(), task.attempt(), directLease);
        int attemptAtDispatch = ownership.attempt();
        CompletableFuture<DispatchResult> future;
        try {
            future = switch (mode) {
                case LOCAL -> dispatcherRouter.dispatchLocal(task);
                case EXTERNAL -> dispatcherRouter.dispatchExternal(task);
                case DEPLOYMENT -> dispatchDeployment(task);
            };
        } catch (Exception ex) {
            if (directLease != null) directLease.release();
            else releaseDispatchSlotOnce(executionRecord, task.attempt(), task.functionName());
            // No transport was created: return the already-acquired capacity here.
            completeExecution(task.executionId(),
                    DispatchResult.warm(InvocationResult.error(mode.name() + "_ERROR", ex.getMessage())),
                    attemptAtDispatch);
            return;
        }

        // Remember cancellation across handle publication. DEPLOYMENT forwards it to the
        // actual HTTP subscription. Cancelling a generic LOCAL future cannot stop its worker.
        executionRecord.attachDispatchHandle(mode == ExecutionMode.LOCAL
                ? new LocalCancellationHandle(future) : future);

        // Release physical capacity before publishing this attempt's logical result:
        // CompletableFuture callbacks otherwise run in reverse registration order and a
        // retry could see the just-finished attempt still occupying its only slot.
        CompletableFuture<DispatchResult> attempt = new CompletableFuture<>();
        future.whenComplete((result, error) -> {
            try {
                if (ownership.lease() != null) ownership.lease().release();
                else releaseDispatchSlotOnce(executionRecord, attemptAtDispatch, task.functionName());
            } finally {
                if (error != null) attempt.completeExceptionally(error);
                else attempt.complete(result);
            }
        });
        long attemptDeadlineMs = task.functionSpec().timeoutMs();
        if (attemptDeadlineMs > 0) attempt.orTimeout(attemptDeadlineMs, TimeUnit.MILLISECONDS);

        attempt.whenComplete((dispatchResult, error) -> {
            if (error instanceof TimeoutException) {
                // Attempt deadline elapsed: conclude the wait and feed the retry policy, but do
                // not release the direct lease here. The raw future's own completion (registered
                // above) releases it, so a non-interruptible LOCAL handler keeps its slot until
                // its work actually ends and the retry cannot acquire a fresh slot meanwhile.

                completeExecution(task.executionId(),
                        DispatchResult.warm(InvocationResult.error("ATTEMPT_TIMEOUT", "Attempt deadline exceeded")),
                        attemptAtDispatch);
                if (mode != ExecutionMode.LOCAL) future.cancel(true);
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
            var result = new CancellableDispatchFuture();
            wakeUpGate.ensureReady(task).whenComplete((ignored, error) -> {
                if (result.isCancelled()) return;
                if (error != null) {
                    result.completeExceptionally(new DeploymentWakeUpException(error));
                    return;
                }
                try {
                    CompletableFuture<DispatchResult> transport = dispatcherRouter.dispatchExternal(task);
                    result.attach(transport);
                    transport.whenComplete((value, failure) -> {
                        if (result.cancellationRequested()) return;
                        if (failure != null) result.completeExceptionally(failure);
                        else result.complete(value);
                    });
                } catch (Exception failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result;
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
        // Retries remain live. A late callback on a terminal record still drives its
        // canonical settlement, but never replaces the selected answer.
        if (completion == null) executionStore.settle(executionRecord);
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
        bestEffort(() -> metrics.retry(functionName));
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
        executionStore.settle(executionRecord);
        bestEffort(() -> {
            recordCompletionMetrics(completion, wonTheConclusion);
            if (completion.result().success()) metrics.success(functionName);
            else metrics.error(functionName);
        });
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
     * Conclude an abandoned invocation and request cancellation of its local transport.
     * Physical work owns capacity until it ends; a non-cooperative LOCAL handler keeps
     * its lease even after the shared answer has expired and the record was archived.
     * Taking the handle remembers cancellation when publication has not happened yet.
     */
    private void handleAdministrativeExpiry(ExecutionRecord executionRecord) {
        boolean wasNonTerminal;
        Future<?> handle;
        synchronized (executionRecord) {
            wasNonTerminal = !isTerminal(executionRecord.state());
            if (wasNonTerminal) {
                executionRecord.markError(new ErrorInfo(EXECUTION_EXPIRED_CODE,
                        "Execution exceeded its maximum lifetime before a dispatch outcome arrived"));
            }
            handle = executionRecord.takeDispatchHandle();
        }
        InvocationTask task = executionRecord.task();
        // Compatibility records can own a bookkeeping slot; active transports release
        // their own capacity on completion, including a cancellation acknowledgement.
        releaseAttemptCapacity(executionRecord, task.attempt(), task.functionName());
        if (handle != null) {
            handle.cancel(true);
        }

        // An invocation that died for maxLifetime still has one end-to-end conclusion: its total,
        // admission to expiry. The store's terminal listener records it at the settle below,
        // guarded against a late dispatch racing this eviction.
        executionStore.settle(executionRecord);
        if (wasNonTerminal) bestEffort(() -> metrics.error(task.functionName()));
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

    private void releaseDispatchSlot(String functionName) {
        enqueuer.releaseDispatchSlot(functionName);
    }

    private void releaseDispatchSlotOnce(ExecutionRecord executionRecord, int attempt, String functionName) {
        if (executionRecord.markDispatchSlotReleased(attempt)) {
            releaseDispatchSlot(functionName);
        }
    }

    /** Legacy completion adapter; production dispatch leases are owned by raw work. */
    private void releaseAttemptCapacity(ExecutionRecord executionRecord, int attempt, String functionName) {
        if (executionRecord.capacityOwnedByTransport()) return;
        if (executionRecord.wasDirectAdmission()) {
            DispatchLease lease = executionRecord.takeDispatchLease();
            if (lease != null) {
                lease.release();
            }
        } else if (executionRecord.wasDispatched()) {
            releaseDispatchSlotOnce(executionRecord, attempt, functionName);
        }
    }

    private static void bestEffort(Runnable observer) {
        try { observer.run(); }
        catch (RuntimeException failure) { log.warn("Completion observer failed", failure); }
    }

    /** Cancellation follows a DEPLOYMENT dispatch across the wake-up/HTTP boundary. */
    private static final class CancellableDispatchFuture extends CompletableFuture<DispatchResult> {
        private Future<?> transport;
        private boolean cancellationRequested;
        synchronized boolean cancellationRequested() { return cancellationRequested; }
        void attach(Future<?> handle) {
            boolean cancel;
            synchronized (this) {
                transport = handle;
                cancel = cancellationRequested;
            }
            if (cancel) handle.cancel(true);
        }
        @Override public boolean cancel(boolean interrupt) {
            Future<?> handle;
            synchronized (this) {
                if (isDone()) return isCancelled();
                cancellationRequested = true;
                handle = transport;
            }
            // Cancel the actual subscriber before publishing the composed cancellation.
            if (handle != null) handle.cancel(interrupt);
            return super.cancel(interrupt);
        }
    }

    /** A LOCAL future has no interrupt contract: only its real completion releases capacity. */
    private record LocalCancellationHandle(CompletableFuture<?> work) implements Future<Object> {
        @Override public boolean cancel(boolean interrupt) { return false; }
        @Override public boolean isCancelled() { return false; }
        @Override public boolean isDone() { return work.isDone(); }
        @Override public Object get() throws java.util.concurrent.ExecutionException, InterruptedException {
            return work.get();
        }
        @Override public Object get(long timeout, TimeUnit unit)
                throws java.util.concurrent.ExecutionException, InterruptedException, TimeoutException {
            return work.get(timeout, unit);
        }
    }

    private static boolean isTerminal(it.unimib.datai.nanofaas.controlplane.execution.ExecutionState state) {
        return state == it.unimib.datai.nanofaas.controlplane.execution.ExecutionState.SUCCESS
                || state == it.unimib.datai.nanofaas.controlplane.execution.ExecutionState.ERROR
                || state == it.unimib.datai.nanofaas.controlplane.execution.ExecutionState.TIMEOUT;
    }
}
