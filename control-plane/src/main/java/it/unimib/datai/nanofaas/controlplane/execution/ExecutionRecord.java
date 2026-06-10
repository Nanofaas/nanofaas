package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.common.model.ErrorInfo;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Mutable execution record with thread-safe state transitions.
 *
 * State transitions are synchronized to ensure consistency between related fields.
 * Use {@link #snapshot()} to get a consistent view of all fields.
 */
public class ExecutionRecord {
    private static final Logger log = LoggerFactory.getLogger(ExecutionRecord.class);

    private final String executionId;
    private final CompletableFuture<InvocationResult> completion;

    // Guarded by 'this' - all mutable state is accessed under synchronization
    private InvocationTask task;
    private ExecutionState state;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant dispatchedAt;
    private ErrorInfo lastError;
    private Object output;
    private boolean coldStart;
    private Long initDurationMs;
    private boolean cleaned;

    public ExecutionRecord(String executionId, InvocationTask task) {
        this.executionId = executionId;
        this.task = task;
        this.completion = new CompletableFuture<>();
        this.state = ExecutionState.QUEUED;
    }

    public String executionId() {
        return executionId;
    }

    public CompletableFuture<InvocationResult> completion() {
        return completion;
    }

    /**
     * Returns a consistent snapshot of the current execution state.
     * All fields are read atomically.
     */
    public synchronized Snapshot snapshot() {
        return new Snapshot(
                executionId,
                task,
                state,
                startedAt,
                finishedAt,
                dispatchedAt,
                output,
                lastError,
                coldStart,
                initDurationMs
        );
    }

    /**
     * Terminal states (SUCCESS, ERROR, TIMEOUT) are final; every transition between
     * non-terminal states (including RUNNING -> QUEUED for retries) is allowed.
     */
    private boolean canTransition(ExecutionState target) {
        if (isTerminalState(state)) {
            log.warn("Invalid state transition {} -> {} for execution {}", state, target, executionId);
            return false;
        }
        return true;
    }

    private static boolean isTerminalState(ExecutionState state) {
        return state == ExecutionState.SUCCESS
                || state == ExecutionState.ERROR
                || state == ExecutionState.TIMEOUT;
    }

    /**
     * Marks the execution as running.
     */
    public synchronized void markRunning() {
        if (!canTransition(ExecutionState.RUNNING)) {
            return;
        }
        this.state = ExecutionState.RUNNING;
        this.startedAt = Instant.now();
    }

    /**
     * Marks the execution as completed with success.
     */
    public synchronized void markSuccess(Object output) {
        if (!canTransition(ExecutionState.SUCCESS)) {
            return;
        }
        this.state = ExecutionState.SUCCESS;
        this.finishedAt = Instant.now();
        this.output = output;
        this.lastError = null;
    }

    /**
     * Marks the execution as completed with error.
     */
    public synchronized void markError(ErrorInfo error) {
        if (!canTransition(ExecutionState.ERROR)) {
            return;
        }
        this.state = ExecutionState.ERROR;
        this.finishedAt = Instant.now();
        this.lastError = error;
        this.output = null;
    }

    /**
     * Marks the execution as timed out.
     */
    public synchronized void markTimeout() {
        if (!canTransition(ExecutionState.TIMEOUT)) {
            return;
        }
        this.state = ExecutionState.TIMEOUT;
        this.finishedAt = Instant.now();
    }

    /**
     * Marks the dispatch time for queue wait calculation.
     */
    public synchronized void markDispatchedAt() {
        this.dispatchedAt = Instant.now();
    }

    /**
     * Records cold start information from runtime headers.
     */
    public synchronized void markColdStart(long initDurationMs) {
        this.coldStart = true;
        this.initDurationMs = initDurationMs;
    }

    /**
     * Releases heavy payloads (request input and response output) to save memory.
     * Should be called after the result has been consumed or is no longer needed.
     */
    public synchronized void cleanup() {
        if (this.cleaned) {
            return;
        }
        this.cleaned = true;
        this.output = null;
        if (this.task != null) {
            // Replace task with one that has no request payload
            this.task = new InvocationTask(
                    task.executionId(),
                    task.functionName(),
                    task.functionSpec(),
                    null, // Clear request
                    task.idempotencyKey(),
                    task.traceId(),
                    task.enqueuedAt(),
                    task.attempt()
            );
        }
    }

    /**
     * Resets the execution for a retry attempt.
     */
    public synchronized void resetForRetry(InvocationTask retryTask) {
        if (!canTransition(ExecutionState.QUEUED)) {
            return;
        }
        this.task = retryTask;
        this.state = ExecutionState.QUEUED;
        this.startedAt = null;
        this.finishedAt = null;
        this.dispatchedAt = null;
        this.lastError = null;
        this.output = null;
        this.coldStart = false;
        this.initDurationMs = null;
        this.cleaned = false;
    }

    // Legacy accessors - kept for backward compatibility but prefer snapshot() for reads

    public synchronized InvocationTask task() {
        return task;
    }

    public synchronized ExecutionState state() {
        return state;
    }

    public synchronized Instant startedAt() {
        return startedAt;
    }

    public synchronized Instant finishedAt() {
        return finishedAt;
    }

    public synchronized boolean isTerminal() {
        return isTerminalState(state);
    }

    public synchronized ErrorInfo lastError() {
        return lastError;
    }

    public synchronized Object output() {
        return output;
    }

    /**
     * Immutable snapshot of execution state for consistent reads.
     */
    public record Snapshot(
            String executionId,
            InvocationTask task,
            ExecutionState state,
            Instant startedAt,
            Instant finishedAt,
            Instant dispatchedAt,
            Object output,
            ErrorInfo lastError,
            boolean coldStart,
            Long initDurationMs
    ) {}
}
