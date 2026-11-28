package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.common.model.ErrorInfo;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadFailedException;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadGateway;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Conclusions that do not come from a local attempt's result: an offloaded call the remote plane
 * answered or failed, an execution the store expired at max-lifetime, and the one end-to-end sample
 * every terminal execution records, whichever path concluded it.
 */
final class OutOfBandConclusions {
    private final ExecutionStore executionStore;
    private final AttemptNotifications notifications;

    OutOfBandConclusions(ExecutionStore executionStore, AttemptNotifications notifications) {
        this.executionStore = executionStore;
        this.notifications = notifications;
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
    void completeOffloadedExecution(String executionId, InvocationResult result) {
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
                    executionRecord.markError(result.error(), result.statusCode(), result.headers());
                }
            }
        }
        // Offload has no dispatch lease, but the record carries the existing logical admission
        // generation so a late callback cannot write replacement-generation notifications.
        settleAndCount(executionRecord, concluded, executionRecord.task(), executionRecord.currentGeneration(), result);
    }

    /**
     * Infrastructure failure of an offloaded call: final by design (no local
     * fallback). The record stores the error; the shared future completes
     * exceptionally so every live idempotent waiter surfaces the same HTTP failure.
     * Archived replays retain its status and retry hint in the outcome.
     *
     * <p>Finalization is unconditional (finding R4), exactly as in
     * {@link #completeOffloadedExecution}: a terminal record does not make this an
     * early return, and the already-definitive result prevails over the late failure.
     */
    void failOffloadedExecution(String executionId, OffloadFailedException failure) {
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
                executionRecord.markFailure(error, failure, failure.statusCode(),
                        failure.retryAfter() == null ? null : java.util.Map.of("Retry-After", failure.retryAfter()));
            }
        }
        settleAndCount(executionRecord, concluded, executionRecord.task(), executionRecord.currentGeneration(),
                InvocationResult.error(error.code(), error.message()));
    }

    /**
     * Conclude an abandoned invocation and request cancellation of its local transport.
     * Physical work owns capacity until it ends; a non-cooperative LOCAL handler keeps
     * its lease even after the shared answer has expired and the record was archived.
     * Taking the handle remembers cancellation when publication has not happened yet.
     */
    void handleAdministrativeExpiry(ExecutionRecord executionRecord) {
        boolean wasNonTerminal;
        Future<?> handle;
        ErrorInfo error = new ErrorInfo(AttemptCoordinator.EXECUTION_EXPIRED_CODE,
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
        executionRecord.releaseUntransferredLease();
        if (handle != null) {
            handle.cancel(true);
        }

        // An invocation that died for maxLifetime still has one end-to-end conclusion: its total,
        // admission to expiry. The store's terminal listener records it at the settle, guarded
        // against a late dispatch racing this eviction.
        settleAndCount(executionRecord, wasNonTerminal, task, generation,
                InvocationResult.error(error.code(), error.message()));
    }

    /**
     * Settles a record an outside event concluded, then counts its success or error once, when
     * that event is the one that decided it and the function is still the generation it was
     * admitted under. Such a conclusion never dispatched, so it has no attempt timings.
     */
    private void settleAndCount(ExecutionRecord executionRecord, boolean decidedHere, InvocationTask task,
                                FunctionGeneration generation, InvocationResult reported) {
        executionStore.settle(executionRecord);
        if (decidedHere && notifications.isCurrentGeneration(task.functionName(), generation)) {
            notifications.completed(task, DispatchResult.warm(reported),
                    AttemptObserver.NO_ATTEMPT, AttemptObserver.NO_ATTEMPT);
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
    void recordTerminalConclusionOnce(ExecutionRecord executionRecord) {
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
        if (!notifications.isCurrentGeneration(functionName, executionRecord.currentGeneration())) {
            return;
        }
        long e2eMs = AttemptCompletion.nanosToMs(executionRecord.admittedAtNanos(), finishedAtNanos);
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
        notifications.terminal(task, result, TimeUnit.MILLISECONDS.toNanos(e2eMs));
    }
}
