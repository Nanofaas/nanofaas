package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.common.model.ErrorInfo;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchAttempt;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationQuotaExceededException;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadFailedException;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadGateway;
import it.unimib.datai.nanofaas.controlplane.queue.QueueFullException;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.RetryScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the attempt state machine: dispatch, retry and completion — retry policy (default 3
 * attempts), timeout handling, tombstoning and the physical-drain/lease-release ordering.
 * {@code ExecutionCompletionHandler} is a thin Spring facade over this class plus the
 * constructor/overload surface the control plane's callers depend on.
 *
 * <p>This class never calls a dispatcher or a readiness gate directly: {@link #transport} is the
 * only door to the outside world, so mode selection and any wake-up wait live entirely on the
 * control plane's side of that boundary (the {@code AttemptTransportAdapter}).
 * Metrics recording stays behind {@link #observer}. This class fences notifications against
 * {@link #capacity}, since it owns the generation decision before notifying the observer.
 */
@SuppressWarnings("FutureReturnValueIgnored") // Callback stages complete lifecycle-owned futures.
public final class AttemptCoordinator {
    private static final Logger log = LoggerFactory.getLogger(AttemptCoordinator.class);

    /**
     * Marks an outcome the administrative-expiry path fabricated because the real
     * one never arrived, as opposed to a genuine runtime error.
     */
    public static final String EXECUTION_EXPIRED_CODE = "EXECUTION_EXPIRED";

    private final ExecutionStore executionStore;
    private final FunctionCapacityRegistry capacity;
    private final RetryScheduler retry;
    private final AttemptTransport transport;
    private final AttemptObserver observer;
    private final RetryBackoff backoff;

    public AttemptCoordinator(ExecutionStore executionStore,
                              FunctionCapacityRegistry capacity,
                              RetryScheduler retry,
                              AttemptTransport transport,
                              AttemptObserver observer) {
        this(executionStore, capacity, retry, transport, observer,
                new RetryBackoff(Duration.ofMillis(100), Duration.ofSeconds(2),
                        () -> ThreadLocalRandom.current().nextDouble()));
    }

    public AttemptCoordinator(ExecutionStore executionStore,
                              FunctionCapacityRegistry capacity,
                              RetryScheduler retry,
                              AttemptTransport transport,
                              AttemptObserver observer,
                              RetryBackoff backoff) {
        this.backoff = Objects.requireNonNull(backoff, "backoff must not be null");
        this.executionStore = Objects.requireNonNull(executionStore, "executionStore must not be null");
        this.capacity = Objects.requireNonNull(capacity, "capacity must not be null");
        this.retry = Objects.requireNonNull(retry, "retry must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
        // The store knows nothing about dispatch slots or shared futures; it only knows a record
        // fell out of inFlight on its own. Closing what that record was actually holding is this
        // class's job.
        this.executionStore.onAdministrativeExpiry(this::handleAdministrativeExpiry);
        // Archiving is the ONLY event common to every terminal policy: normal completion, sync
        // timeout, administrative expiry, offload conclusion, and the queue-side terminations a
        // module owns (queue-wait timeout, function removed while queued) — which this class
        // never sees at all. Hanging the end-to-end conclusion here is what makes "exactly one
        // per invocation" true for all of them instead of only for the paths that pass through
        // publishFinalCompletion; the record's own guard keeps it to one.
        this.executionStore.onTerminal(this::recordTerminalConclusionOnce);
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
        DispatchOwnership lease = capacity.tryAcquireLease(
                task.functionName(), task.functionSpec().concurrency());
        if (lease == null) {
            throw new QueueFullException();
        }
        dispatchInternal(task, lease);
    }

    /**
     * Shared body for direct, retry and queue dispatch. All production schedulers pass
     * their acquired lease with the task. A null lease supports legacy in-process callers.
     */
    private void dispatchInternal(InvocationTask task, DispatchOwnership directLease) {
        ExecutionRecord executionRecord = executionStore.getOrNull(task.executionId());
        if (executionRecord == null) {
            releaseUndispatched(task, directLease);
            return;
        }

        boolean terminal;
        ExecutionRecord.PhysicalInput physicalInput = null;
        Throwable inputFailure = null;
        synchronized (executionRecord) {
            terminal = executionRecord.isTerminal();
            if (!terminal) {
                try {
                    // Acquire the physical reader while the terminal-state check is still
                    // protected by the record monitor. Administrative settlement may release
                    // the logical/base owners afterwards, but this reader then keeps the input
                    // charged until the raw transport or LOCAL worker really drains.
                    // Not try-with-resources (java:S2095): ownership moves to the attempt, which
                    // closes it on a failed submit or once the transport drains.
                    physicalInput = executionRecord.openPhysicalInput(task); // NOSONAR
                    task.releaseQueuedInput();
                    if (directLease != null) {
                        executionRecord.attachDispatchLease(directLease);
                    }
                    executionRecord.transportOwnsCapacity();
                    executionRecord.markRunning();
                    executionRecord.markDispatchedAt();
                } catch (RuntimeException | Error failure) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
                    inputFailure = failure;
                }
            }
        }
        if (terminal) {
            releaseUndispatched(task, directLease);
            executionStore.settle(executionRecord);
            return;
        }
        if (inputFailure != null) {
            failInputPreparation(task, directLease, physicalInput, inputFailure);
            return;
        }
        bestEffort(() -> observer.submitted(task));

        ExecutionMode mode = task.functionSpec().executionMode();
        DispatchAttempt ownership = new DispatchAttempt(task.executionId(), task.attempt(), directLease);
        AttemptHandle handle = submitOrConclude(task, physicalInput, ownership, mode);
        if (handle != null) {
            trackAttempt(task, executionRecord, handle, ownership, physicalInput, mode);
        }
    }

    /** Returns the queued input and any direct lease of a dispatch that never started. */
    private static void releaseUndispatched(InvocationTask task, DispatchOwnership directLease) {
        task.releaseQueuedInput();
        if (directLease != null) {
            directLease.release();
        }
    }

    private void failInputPreparation(InvocationTask task, DispatchOwnership directLease,
                                      ExecutionRecord.PhysicalInput physicalInput, Throwable inputFailure) {
        if (physicalInput != null) physicalInput.close();
        if (directLease != null) directLease.release();
        if (inputFailure instanceof InvocationQuotaExceededException quotaFailure) {
            throw quotaFailure;
        }
        task.releaseQueuedInput();
        if (inputFailure instanceof Error error) {
            completeOffloadedExecution(task.executionId(), InvocationResult.error(
                    "INPUT_PREPARATION_ERROR", error.getMessage()));
            throw error;
        }
        completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.error(
                "INPUT_CAPACITY_EXHAUSTED", inputFailure.getMessage())), task.attempt());
    }

    /** Submits the attempt, or concludes it and returns null when no transport was created. */
    private AttemptHandle submitOrConclude(InvocationTask task, ExecutionRecord.PhysicalInput attemptInput,
                                           DispatchAttempt ownership, ExecutionMode mode) {
        InvocationTask physicalTask = attemptInput.task();
        try {
            return transport.submit(physicalTask);
        } catch (RuntimeException | Error ex) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
            attemptInput.close();
            if (ownership.lease() != null) ownership.lease().release();
            // No transport was created: return the already-acquired capacity here.
            InvocationResult failure = InvocationResult.error(mode.name() + "_ERROR", ex.getMessage());
            if (ex instanceof Error error) {
                completeOffloadedExecution(task.executionId(), failure);
                throw error;
            }
            completeExecution(task.executionId(), DispatchResult.warm(failure), ownership.attempt());
            return null;
        }
    }

    private void trackAttempt(InvocationTask task, ExecutionRecord executionRecord, AttemptHandle handle,
                              DispatchAttempt ownership, ExecutionRecord.PhysicalInput attemptInput,
                              ExecutionMode mode) {
        int attemptAtDispatch = ownership.attempt();
        CompletableFuture<DispatchResult> future = handle.outcome();

        // Remember cancellation across handle publication: however the transport chose to
        // represent it (a no-op for a non-interruptible LOCAL worker, a real cancellable
        // subscription otherwise). Cancelling is the transport's decision, not this class's.
        executionRecord.attachDispatchHandle(handle.cancellation());

        // Cancellation can complete the logical outcome wrapper before readiness/transport
        // has stopped retaining the request. Only the independent raw-drain signal releases
        // the dispatch lease and physical input. The derived resourcesDrained stage also
        // preserves the retry invariant: a next attempt cannot observe completion before the
        // previous attempt returned its physical capacity.
        CompletableFuture<DispatchResult> attempt = new CompletableFuture<>();
        CompletableFuture<Void> resourcesDrained = handle.drained().handle((ignored, drainError) -> {
            try {
                if (ownership.lease() != null) ownership.lease().release();
            } finally {
                attemptInput.close();
            }
            return null;
        });
        future.whenComplete((result, error) -> resourcesDrained.whenComplete((ignored, drainError) -> {
            if (error != null) attempt.completeExceptionally(error);
            else if (drainError != null) attempt.completeExceptionally(drainError);
            else attempt.complete(result);
        }));
        long attemptDeadlineMs = task.functionSpec().timeoutMs();
        if (attemptDeadlineMs > 0) attempt.orTimeout(attemptDeadlineMs, TimeUnit.MILLISECONDS);

        attempt.whenComplete((dispatchResult, error) ->
                concludeAttempt(task, mode, future, attemptAtDispatch, dispatchResult, error));
    }

    private void concludeAttempt(InvocationTask task, ExecutionMode mode, CompletableFuture<DispatchResult> future,
                                 int attemptAtDispatch, DispatchResult dispatchResult, Throwable error) {
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
    }

    private static Throwable deploymentWakeUpFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof DeploymentWakeUpException) {
                return current.getCause() != null ? current.getCause() : current;
            }
        }
        return null;
    }

    /**
     * Marker the transport adapter wraps a deployment wake-up failure in, so this class can tell
     * "the managed deployment never scaled up" apart from an ordinary transport failure without
     * knowing anything about how wake-up works. Public: the adapter that constructs it
     * lives in the control plane, a different module.
     */
    public static final class DeploymentWakeUpException extends RuntimeException {
        public DeploymentWakeUpException(Throwable cause) {
            super(cause.getMessage(), cause);
        }
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
        Conclusion conclusion;
        synchronized (executionRecord) {
            conclusion = completeUnderLock(executionRecord, dispatchResult, completedAttempt);
        }
        // The next attempt is PREPARED under the monitor above and PUBLISHED here, after it was
        // released: publishing walks into the scheduler's own gate, and a queue that had to take
        // the record monitor to accept work would close a lock cycle (ADR 0002). Nothing else
        // can dispatch this attempt meanwhile — it is not in any queue until this line runs.
        if (conclusion.retry() != null) {
            PendingRetry pending = conclusion.retry();
            if (isCurrentGeneration(pending.functionName(), pending.generation())) {
                bestEffort(() -> observer.retried(pending.task()));
            }
            FinalCompletion completion = publishRetry(executionRecord, pending);
            publishFinalCompletion(executionRecord, completion);
            if (completion == null) executionStore.settle(executionRecord);
        } else {
            FinalCompletion completion = conclusion.completion();
            publishFinalCompletion(executionRecord, completion);
            // Retries remain live. A late callback on a terminal record still drives its
            // canonical settlement, but never replaces the selected answer.
            if (completion == null) executionStore.settle(executionRecord);
        }
    }

    /**
     * State transitions only; final-completion notification and future completion happen
     * outside the record monitor (see publishFinalCompletion) so synchronous whenComplete
     * callbacks never run while the lock is held. Retry publication is likewise entirely
     * outside this method (see completeExecution above) — this method only prepares it.
     */
    private Conclusion completeUnderLock(ExecutionRecord executionRecord,
                                         DispatchResult dispatchResult,
                                         Integer completedAttempt) {
        InvocationResult result = dispatchResult.result();
        InvocationTask currentTask = executionRecord.task();
        if (completedAttempt != null && currentTask.attempt() != completedAttempt) {
            return Conclusion.NONE;
        }

        String functionName = currentTask.functionName();
        // Captured before releaseAttemptCapacity, which detaches the lease on the
        // direct-admission path: this is the generation the attempt was admitted under,
        // not whatever the registry considers active by the time metrics are recorded.
        FunctionGeneration generation = executionRecord.currentGeneration();
        releaseAttemptCapacity(executionRecord);
        if (executionRecord.isTerminal()) {
            return Conclusion.NONE;
        }

        boolean shouldRetry = !result.success()
                && currentTask.attempt() <= currentTask.functionSpec().maxRetries();
        if (shouldRetry) {
            // prepareRetry always terminates the retry path: the execution is back in QUEUED
            // with its next attempt ready to publish, and never falls through to final completion.
            Instant due = backoff.notBefore(currentTask.attempt(), executionRecord.now(),
                    dispatchResult.retryNotBefore());
            log.debug("Retry execution {} failedAttempt={} nextAttempt={} notBefore={} hintPresent={}",
                    executionRecord.executionId(), currentTask.attempt(), currentTask.attempt() + 1,
                    due, dispatchResult.retryNotBefore() != null);
            return Conclusion.of(prepareRetry(executionRecord, currentTask, result, generation, due));
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
        return Conclusion.of(new FinalCompletion(functionName, result, latencyMs, queueWaitMs,
                dispatchResult.coldStart(), dispatchResult.initDurationMs(), false, generation));
    }

    private static Long nanosToMs(long startNanos, long endNanos) {
        return TimeUnit.NANOSECONDS.toMillis(endNanos - startNanos);
    }

    /**
     * Builds the next attempt and puts the record back in QUEUED, under the record monitor.
     * Nothing is published here: the returned {@link PendingRetry} is handed back to
     * {@link #completeExecution} once the caller has released the monitor.
     */
    private PendingRetry prepareRetry(ExecutionRecord executionRecord, InvocationTask currentTask,
                                      InvocationResult result, FunctionGeneration generation, Instant notBefore) {
        String functionName = currentTask.functionName();
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
        // The queue-entry input owner is taken here, with the reset, so the attempt that is about
        // to be published already holds it when it becomes visible to a scheduler.
        return new PendingRetry(executionRecord.prepareForQueue(), retryTask.attempt(),
                functionName, result, generation, notBefore);
    }

    /**
     * Publishes the prepared attempt OUTSIDE the record monitor. On failure the same execution
     * concludes here, after re-validating that it is still parked on this attempt: an
     * administrative expiry or another terminal path that won the record while the publication
     * was failing owns the conclusion instead.
     *
     * @return a final completion when the attempt could not be published, null when it is queued
     */
    // S2445: the record IS the per-execution lock object; a dedicated monitor would serialize all executions.
    @SuppressWarnings("java:S2445")
    private FinalCompletion publishRetry(ExecutionRecord executionRecord, PendingRetry pending) {
        InvocationTask task = pending.task();
        boolean enqueued;
        try {
            enqueued = retry.enqueue(task, pending.notBefore(), () -> {
                task.releaseQueuedInput();
                publishFinalCompletion(executionRecord, concludeExhaustedRetry(executionRecord, pending));
            });
        } catch (RuntimeException | Error ex) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
            task.releaseQueuedInput();
            log.warn("Retry scheduling failed for execution {}, completing with error: {}",
                    executionRecord.executionId(), ex.toString());
            return concludeExhaustedRetry(executionRecord, pending);
        }
        if (enqueued) {
            return null;
        }
        // Expected refusal (e.g. queue full); anything thrown above is belt-and-braces. Both get
        // the same terminal treatment; only the log line differs.
        task.releaseQueuedInput();
        log.warn("Retry queue full for execution {}, completing with error", executionRecord.executionId());
        return concludeExhaustedRetry(executionRecord, pending);
    }

    private FinalCompletion concludeExhaustedRetry(ExecutionRecord executionRecord, PendingRetry pending) {
        synchronized (executionRecord) { // NOSONAR (java:S2445): this object is its own monitor by design; every path locks the same instance
            if (executionRecord.task().attempt() != pending.attempt()
                    || executionRecord.isTerminal()) {
                return null;
            }
            return retryExhaustedUnderLock(executionRecord, pending.functionName(), pending.result(), pending.generation());
        }
    }

    /**
     * The retry could not be scheduled: the invocation concludes in error here, with the SAME
     * error the attempt that triggered the retry already had (there is no new failure to
     * report — the attempt itself failed; only republishing it failed). The attempt never
     * dispatched again, so there is no service time and no wait for it — but the invocation's
     * total, admission to this conclusion, is still real and is what the terminal e2e sample
     * records.
     */
    private static FinalCompletion retryExhaustedUnderLock(ExecutionRecord executionRecord,
                                                           String functionName,
                                                           InvocationResult result,
                                                           FunctionGeneration generation) {
        executionRecord.markError(result.error());
        return FinalCompletion.retryExhausted(functionName, result, generation);
    }

    private void publishFinalCompletion(ExecutionRecord executionRecord, FinalCompletion completion) {
        if (completion == null) {
            return;
        }
        String functionName = completion.functionName();
        executionStore.settle(executionRecord);
        // A completion whose function was removed and re-registered since admission (I7) must
        // not attribute its sample to the new generation's meters.
        if (!isCurrentGeneration(functionName, completion.generation())) {
            return;
        }
        InvocationTask task = executionRecord.task();
        // Cold/warm and the per-attempt timers are a property of a dispatch that actually ran. A
        // retry that never scheduled (queue full) dispatched nothing, so it reports NO_ATTEMPT —
        // success/error still needs recording (it always did, even for this case), but nothing
        // attempt-level does.
        if (completion.retryExhausted()) {
            DispatchResult syntheticResult = DispatchResult.warm(completion.result());
            bestEffort(() -> observer.completed(task, syntheticResult, AttemptObserver.NO_ATTEMPT, AttemptObserver.NO_ATTEMPT));
            return;
        }
        long queueWaitNanos = completion.queueWaitMs() != null && completion.queueWaitMs() >= 0
                ? TimeUnit.MILLISECONDS.toNanos(completion.queueWaitMs())
                : AttemptObserver.NOT_MEASURABLE;
        long serviceNanos = completion.latencyMs() != null
                ? TimeUnit.MILLISECONDS.toNanos(completion.latencyMs())
                : AttemptObserver.NOT_MEASURABLE;
        DispatchResult attemptResult = new DispatchResult(completion.result(), completion.coldStart(),
                completion.initDurationMs());
        bestEffort(() -> observer.completed(task, attemptResult, queueWaitNanos, serviceNanos));
    }

    /**
     * What one completion decided under the record monitor: either the invocation's final
     * completion, or a next attempt still to be published, never both.
     */
    private record Conclusion(FinalCompletion completion, PendingRetry retry) {
        private static final Conclusion NONE = new Conclusion(null, null);

        static Conclusion of(FinalCompletion completion) {
            return new Conclusion(completion, null);
        }

        static Conclusion of(PendingRetry retry) {
            return new Conclusion(null, retry);
        }
    }

    /** A next attempt prepared under the record monitor, waiting to be published outside it. */
    private record PendingRetry(InvocationTask task,
                                int attempt,
                                String functionName,
                                InvocationResult result,
                                FunctionGeneration generation,
                                Instant notBefore) {
    }

    private record FinalCompletion(String functionName,
                                   InvocationResult result,
                                   Long latencyMs,
                                   Long queueWaitMs,
                                   boolean coldStart,
                                   Long initDurationMs,
                                   boolean retryExhausted,
                                   FunctionGeneration generation) {
        static FinalCompletion retryExhausted(String functionName, InvocationResult result,
                                              FunctionGeneration generation) {
            return new FinalCompletion(functionName, result, null, null, false, null, true, generation);
        }
    }

    /**
     * Completion path for offloaded executions: no retry and no dispatch-slot
     * release (offloaded calls never acquired one), just state + the shared future.
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
            concluded = !executionRecord.isTerminal();
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
        // Offload has no dispatch lease, but the record carries the existing logical admission
        // generation so a late callback cannot write replacement-generation notifications.
        if (concluded && isCurrentGeneration(executionRecord.task().functionName(), executionRecord.currentGeneration())) {
            InvocationTask task = executionRecord.task();
            DispatchResult syntheticResult = DispatchResult.warm(result);
            bestEffort(() -> observer.completed(task, syntheticResult, AttemptObserver.NO_ATTEMPT, AttemptObserver.NO_ATTEMPT));
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
            concluded = !executionRecord.isTerminal();
            if (concluded) {
                executionRecord.markFailure(error, failure);
            }
        }
        executionStore.settle(executionRecord);
        // See completeOffloadedExecution: the logical admission generation fences this path too.
        if (concluded && isCurrentGeneration(executionRecord.task().functionName(), executionRecord.currentGeneration())) {
            InvocationTask task = executionRecord.task();
            DispatchResult syntheticResult = DispatchResult.warm(InvocationResult.error(error.code(), error.message()));
            bestEffort(() -> observer.completed(task, syntheticResult, AttemptObserver.NO_ATTEMPT, AttemptObserver.NO_ATTEMPT));
        }
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
        ErrorInfo error = new ErrorInfo(EXECUTION_EXPIRED_CODE,
                "Execution exceeded its maximum lifetime before a dispatch outcome arrived");
        synchronized (executionRecord) { // NOSONAR (java:S2445): this object is its own monitor by design; every path locks the same instance
            wasNonTerminal = !executionRecord.isTerminal();
            if (wasNonTerminal) {
                executionRecord.markError(error);
            }
            handle = executionRecord.takeDispatchHandle();
        }
        InvocationTask task = executionRecord.task();
        FunctionGeneration generation = executionRecord.currentGeneration();
        // Compatibility records can own a bookkeeping slot; active transports release
        // their own capacity on completion, including a cancellation acknowledgement.
        releaseAttemptCapacity(executionRecord);
        if (handle != null) {
            handle.cancel(true);
        }

        // An invocation that died for maxLifetime still has one end-to-end conclusion: its total,
        // admission to expiry. The store's terminal listener records it at the settle below,
        // guarded against a late dispatch racing this eviction.
        executionStore.settle(executionRecord);
        if (wasNonTerminal && isCurrentGeneration(task.functionName(), generation)) {
            DispatchResult syntheticResult = DispatchResult.warm(InvocationResult.error(error.code(), error.message()));
            bestEffort(() -> observer.completed(task, syntheticResult, AttemptObserver.NO_ATTEMPT, AttemptObserver.NO_ATTEMPT));
        }
    }

    /**
     * The single end-to-end conclusion for an invocation that became terminal — normal
     * completion, a sync caller's timeout, an administrative expiry, or an offloaded call
     * concluded by the remote plane, and the queue-side terminations a module owns that this
     * class never otherwise sees — recorded exactly once, from the store's own terminal
     * transition, regardless of which path got there first. The record's guard makes every path
     * but the first a no-op.
     */
    private void recordTerminalConclusionOnce(ExecutionRecord executionRecord) {
        Long finishedAtNanos = executionRecord.finishedAtNanos();
        if (finishedAtNanos == null) {
            return;
        }
        // The measurability check comes BEFORE the guard is claimed: claiming first would burn
        // the invocation's one conclusion on a record with no finishedAtNanos to record, and the
        // real terminal path arriving afterwards would then find the guard spent and stay silent.
        if (!executionRecord.markMetricsRecorded()) {
            return;
        }
        InvocationTask task = executionRecord.task();
        String functionName = task.functionName();
        if (!isCurrentGeneration(functionName, executionRecord.currentGeneration())) {
            return;
        }
        long e2eMs = nanosToMs(executionRecord.admittedAtNanos(), finishedAtNanos);
        if (e2eMs < 0) {
            return;
        }
        // Not executionRecord.completion().getNow(null): the shared future completes
        // EXCEPTIONALLY for an offload infrastructure failure (ExecutionRecord.markFailure), and
        // getNow() on an exceptionally-completed future re-throws instead of returning — exactly
        // the failure that once made this listener silently skip its one conclusion. Not
        // executionRecord.snapshot() either: the completion hot path is read-under-lock by
        // individual field, never by the multi-field snapshot copy (see
        // ExecutionCompletionHandlerTest.completeExecution_withSuccess_readsFieldsUnderLockWithoutSnapshot).
        // This class's own AttemptObserver.terminal() implementation only needs enough of the
        // result to be a well-formed InvocationResult, not the full response envelope (output/
        // statusCode/headers/encoding) a caller already received from the shared completion
        // future — output is deliberately omitted here, unlike ExecutionRecord.publishTerminal.
        ExecutionState state = executionRecord.state();
        InvocationResult result;
        if (state == ExecutionState.SUCCESS) {
            result = InvocationResult.success(null);
        } else {
            ErrorInfo error = executionRecord.lastError() != null
                    ? executionRecord.lastError() : new ErrorInfo("TIMEOUT", "Execution timed out");
            result = new InvocationResult(false, null, error);
        }
        bestEffort(() -> observer.terminal(task, result, TimeUnit.MILLISECONDS.toNanos(e2eMs)));
    }

    /** Untransferred handles may be concluded here; dispatched capacity belongs to physical work. */
    private void releaseAttemptCapacity(ExecutionRecord executionRecord) {
        if (executionRecord.capacityOwnedByTransport()) return;
        DispatchOwnership lease = executionRecord.takeDispatchLease();
        if (lease != null) lease.release();
    }

    /**
     * True when a completion may still attribute to {@code functionName}'s meters: either no
     * generation was captured at admission (nothing to compare against), or the captured
     * generation is still the one the capacity registry considers active. False only when the
     * function was removed and re-registered under a new generation since admission (I7).
     * Reproduces {@code Metrics.isCurrentGeneration}'s exact semantics against this class's own
     * {@link FunctionCapacityRegistry} reference, since metrics recording moved behind
     * {@link AttemptObserver} but the fencing decision belongs here.
     */
    private boolean isCurrentGeneration(String functionName, FunctionGeneration admittedGeneration) {
        if (admittedGeneration == null) {
            return true;
        }
        FunctionGeneration active = capacity.activeGeneration(functionName);
        return active == null || active.equals(admittedGeneration);
    }

    private static void bestEffort(Runnable observation) {
        try { observation.run(); }
        catch (RuntimeException failure) { log.warn("Attempt observer failed", failure); }
    }
}
