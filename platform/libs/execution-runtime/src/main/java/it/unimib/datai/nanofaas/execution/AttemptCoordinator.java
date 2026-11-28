package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.controlplane.execution.PhysicalInput;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchAttempt;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadFailedException;
import it.unimib.datai.nanofaas.controlplane.queue.QueueFullException;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.RetryScheduler;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The attempt state machine's entry point. This class dispatches an attempt and tracks it until
 * its transport has drained (timeouts and the physical-drain/lease-release ordering); what the
 * attempt's result then does to the execution, retry or final answer, is {@link AttemptCompletion}'s;
 * conclusions from outside a local attempt (offload, administrative expiry, the end-to-end sample)
 * are {@link OutOfBandConclusions}'. {@code ExecutionCompletionHandler} is a thin Spring facade over
 * this class plus the constructor/overload surface the control plane's callers depend on.
 *
 * <p>Nothing here calls a dispatcher or a readiness gate directly: the {@link AttemptTransport} is
 * the only door to the outside world, so mode selection and any wake-up wait live entirely on the
 * control plane's side of that boundary (the {@code AttemptTransportAdapter}). Metrics recording
 * stays behind the {@link AttemptObserver}, reached through {@link AttemptNotifications}, which
 * also holds the generation fence.
 */
@SuppressWarnings("FutureReturnValueIgnored") // Callback stages complete lifecycle-owned futures.
public final class AttemptCoordinator {
    /**
     * Marks an outcome the administrative-expiry path fabricated because the real
     * one never arrived, as opposed to a genuine runtime error.
     */
    public static final String EXECUTION_EXPIRED_CODE = "EXECUTION_EXPIRED";

    private final ExecutionStore executionStore;
    private final FunctionCapacityRegistry capacity;
    private final AttemptTransport transport;
    private final AttemptNotifications notifications;
    private final AttemptCompletion completion;
    private final OutOfBandConclusions conclusions;

    public AttemptCoordinator(ExecutionStore executionStore,
                              FunctionCapacityRegistry capacity,
                              RetryScheduler retry,
                              AttemptTransport transport,
                              AttemptObserver observer,
                              RetryBackoff backoff) {
        Objects.requireNonNull(backoff, "backoff must not be null");
        this.executionStore = Objects.requireNonNull(executionStore, "executionStore must not be null");
        this.capacity = Objects.requireNonNull(capacity, "capacity must not be null");
        Objects.requireNonNull(retry, "retry must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.notifications = new AttemptNotifications(
                Objects.requireNonNull(observer, "observer must not be null"), capacity);
        this.completion = new AttemptCompletion(executionStore, retry, backoff, notifications);
        this.conclusions = new OutOfBandConclusions(executionStore, notifications);
        // The store knows nothing about dispatch slots or shared futures; it only knows a record
        // fell out of inFlight on its own. Closing what that record was actually holding is this
        // package's job.
        this.executionStore.onAdministrativeExpiry(conclusions::handleAdministrativeExpiry);
        // Archiving is the ONLY event common to every terminal policy: normal completion, sync
        // timeout, administrative expiry, offload conclusion, and the queue-side terminations a
        // module owns (queue-wait timeout, function removed while queued) — which this package
        // never sees at all. Hanging the end-to-end conclusion here is what makes "exactly one
        // per invocation" true for all of them; the record's own guard keeps it to one.
        this.executionStore.onTerminal(conclusions::recordTerminalConclusionOnce);
    }

    /** Queue and retry dispatch: the scheduler acquired the attempt's capacity lease and it travels with the task. */
    public void dispatch(InvocationTask task) {
        DispatchOwnership lease = task.dispatchLease();
        if (lease == null) {
            throw new IllegalArgumentException("a dispatched task must carry the capacity lease its scheduler acquired");
        }
        dispatchInternal(task, lease);
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
     * Shared body for direct, retry and queue dispatch, with the capacity lease the attempt owns.
     */
    private void dispatchInternal(InvocationTask task, DispatchOwnership directLease) {
        ExecutionRecord executionRecord = executionStore.getOrNull(task.executionId());
        if (executionRecord == null) {
            releaseUndispatched(task, directLease);
            return;
        }

        boolean terminal;
        PhysicalInput physicalInput = null;
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
        notifications.submitted(task);

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
        directLease.release();
    }

    private void failInputPreparation(InvocationTask task, DispatchOwnership directLease,
                                      PhysicalInput physicalInput, Throwable inputFailure) {
        if (physicalInput != null) physicalInput.close();
        directLease.release();
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
    private AttemptHandle submitOrConclude(InvocationTask task, PhysicalInput attemptInput,
                                           DispatchAttempt ownership, ExecutionMode mode) {
        InvocationTask physicalTask = attemptInput.task().withDispatchLease(ownership.lease());
        try {
            return transport.submit(physicalTask);
        } catch (RuntimeException | Error ex) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
            attemptInput.close();
            ownership.lease().release();
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
                              DispatchAttempt ownership, PhysicalInput attemptInput,
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
                ownership.lease().release();
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
        completion.complete(executionId, dispatchResult, completedAttempt);
    }

    /**
     * Completion path for offloaded executions: no retry and no dispatch-slot release (offloaded
     * calls never acquired one), just state and the shared future. See {@link OutOfBandConclusions}.
     */
    public void completeOffloadedExecution(String executionId, InvocationResult result) {
        conclusions.completeOffloadedExecution(executionId, result);
    }

    /** Infrastructure failure of an offloaded call: final by design (no local fallback). */
    public void failOffloadedExecution(String executionId, OffloadFailedException failure) {
        conclusions.failOffloadedExecution(executionId, failure);
    }
}
