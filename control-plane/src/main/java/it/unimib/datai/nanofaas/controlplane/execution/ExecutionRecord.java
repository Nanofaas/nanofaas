package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.common.model.ErrorInfo;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Mutable execution record with thread-safe state transitions.
 *
 * State transitions are synchronized to ensure consistency between related fields.
 * Use {@link #snapshot()} to get a consistent view of all fields.
 */
public class ExecutionRecord {
    private static final Logger log = LoggerFactory.getLogger(ExecutionRecord.class);
    private static final Map<ExecutionState, EnumSet<ExecutionState>> ALLOWED_TRANSITIONS;

    static {
        ALLOWED_TRANSITIONS = new EnumMap<>(ExecutionState.class);
        ALLOWED_TRANSITIONS.put(ExecutionState.QUEUED, EnumSet.of(ExecutionState.RUNNING, ExecutionState.TIMEOUT, ExecutionState.ERROR));
        ALLOWED_TRANSITIONS.put(ExecutionState.RUNNING, EnumSet.of(ExecutionState.SUCCESS, ExecutionState.ERROR, ExecutionState.TIMEOUT, ExecutionState.QUEUED));
        ALLOWED_TRANSITIONS.put(ExecutionState.SUCCESS, EnumSet.noneOf(ExecutionState.class));
        ALLOWED_TRANSITIONS.put(ExecutionState.ERROR, EnumSet.noneOf(ExecutionState.class));
        ALLOWED_TRANSITIONS.put(ExecutionState.TIMEOUT, EnumSet.noneOf(ExecutionState.class));
    }

    private final String executionId;
    private final CompletableFuture<InvocationResult> completion;

    // Guarded by 'this' - all mutable state is accessed under synchronization
    private InvocationTask task;
    private ExecutionState state;
    private Instant startedAt;
    private Instant finishedAt;
    private ErrorInfo lastError;
    private Object output;

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
                output,
                lastError
        );
    }

    private void validateTransition(ExecutionState target) {
        EnumSet<ExecutionState> allowed = ALLOWED_TRANSITIONS.getOrDefault(state, EnumSet.noneOf(ExecutionState.class));
        if (!allowed.contains(target)) {
            log.warn("Invalid state transition {} -> {} for execution {}", state, target, executionId);
        }
    }

    /**
     * Marks the execution as running.
     */
    public synchronized void markRunning() {
        validateTransition(ExecutionState.RUNNING);
        this.state = ExecutionState.RUNNING;
        this.startedAt = Instant.now();
    }

    /**
     * Marks the execution as completed with success.
     */
    public synchronized void markSuccess(Object output) {
        validateTransition(ExecutionState.SUCCESS);
        this.state = ExecutionState.SUCCESS;
        this.finishedAt = Instant.now();
        this.output = output;
        this.lastError = null;
    }

    /**
     * Marks the execution as completed with error.
     */
    public synchronized void markError(ErrorInfo error) {
        validateTransition(ExecutionState.ERROR);
        this.state = ExecutionState.ERROR;
        this.finishedAt = Instant.now();
        this.lastError = error;
        this.output = null;
    }

    /**
     * Marks the execution as timed out.
     */
    public synchronized void markTimeout() {
        validateTransition(ExecutionState.TIMEOUT);
        this.state = ExecutionState.TIMEOUT;
        this.finishedAt = Instant.now();
    }

    /**
     * Resets the execution for a retry attempt.
     */
    public synchronized void resetForRetry(InvocationTask retryTask) {
        validateTransition(ExecutionState.QUEUED);
        this.task = retryTask;
        this.state = ExecutionState.QUEUED;
        this.startedAt = null;
        this.finishedAt = null;
        this.lastError = null;
        this.output = null;
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

    public synchronized ErrorInfo lastError() {
        return lastError;
    }

    public synchronized Object output() {
        return output;
    }

    // Legacy setters - prefer the mark* methods for state transitions

    /**
     * @deprecated Use {@link #resetForRetry(InvocationTask)} instead
     */
    @Deprecated
    public synchronized void updateTask(InvocationTask newTask) {
        this.task = newTask;
    }

    /**
     * @deprecated Use {@link #markRunning()}, {@link #markSuccess(Object)}, etc.
     */
    @Deprecated
    public synchronized void state(ExecutionState state) {
        this.state = state;
    }

    /**
     * @deprecated Use {@link #markRunning()}
     */
    @Deprecated
    public synchronized void startedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    /**
     * @deprecated Use mark* methods instead
     */
    @Deprecated
    public synchronized void finishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    /**
     * @deprecated Use {@link #markError(ErrorInfo)}
     */
    @Deprecated
    public synchronized void lastError(ErrorInfo lastError) {
        this.lastError = lastError;
    }

    /**
     * @deprecated Use {@link #markSuccess(Object)}
     */
    @Deprecated
    public synchronized void output(Object output) {
        this.output = output;
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
            Object output,
            ErrorInfo lastError
    ) {}
}
