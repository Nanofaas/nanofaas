package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.common.model.ErrorInfo;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacity;
import it.unimib.datai.nanofaas.controlplane.offload.PlannedInvocationRoute;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * Mutable execution record with thread-safe state transitions.
 *
 * <p>The record is its own monitor: every mutable field, and the {@link AttemptOwnership} it
 * delegates the current attempt's transport to, is read and written under it. Use
 * {@link #snapshot()} to get a consistent view of all fields.
 */
public class ExecutionRecord {
    private static final Logger log = LoggerFactory.getLogger(ExecutionRecord.class);
    private static final TerminalAnswer NO_ANSWER = new TerminalAnswer(null, null, null, null, null, null);

    private final String executionId;
    private final ExecutionInputResources inputResources;
    private final CompletableFuture<InvocationResult> completion;
    /**
     * Whether anyone can still ask about this execution once it has finished.
     *
     * <p>An ASYNC caller holds nothing but the id, so {@code GET /v1/executions/{id}}
     * is its only way of learning the outcome. A keyed execution has to be findable by
     * a retry replaying that key. A plain synchronous one has already had its answer
     * handed back on the connection the caller was holding.
     *
     * <p>Decided once, at construction, and deliberately not read from the current task:
     * a retry replaces the task with one whose idempotency key is null (the retry is
     * internal and must not claim the key again), so asking the task later would
     * demote exactly the executions that had trouble - and idempotency would quietly
     * stop working for them.
     */
    private final boolean readableAfterFinishing;

    /**
     * The idempotency key this execution was admitted under, captured once at
     * construction and deliberately not read back from the current task: a retry
     * replaces the task with one whose key is null (the retry is internal and must
     * not claim the key again), so asking the task later would lose the very key the
     * {@link IdempotencyStore} must keep bound until completion. Null for unkeyed
     * executions.
     */
    private final String idempotencyKey;

    private final TimeSource timeSource;

    /**
     * The instant the caller was admitted, and its monotonic counterpart. Deliberately final and
     * separate from {@code task.enqueuedAt()}: a retry replaces the task (and with it the enqueue
     * instant of the current attempt), but the invocation began here and its end-to-end duration
     * must be measured from here, retries included.
     */
    private final Instant admittedAt;
    private final long admittedAtNanos;

    private final AttemptOwnership ownership;

    // Guarded by 'this'.
    private InvocationTask task;
    private ExecutionState state;
    /** Set by the terminal transition, cleared by a retry; null while the execution is live. */
    private TerminalAnswer answer;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant dispatchedAt;
    /** Monotonic enqueue instant of the current attempt (the first attempt is the admission itself). */
    private long attemptEnqueuedAtNanos;
    /** Monotonic dispatch/completion instants of the current attempt. */
    private Long startedAtNanos;
    private Long finishedAtNanos;
    private boolean coldStart;
    private Long initDurationMs;
    /** The offload plan's admission decision, one for the whole execution including its retries. */
    private PlannedInvocationRoute plannedRoute;
    /** The node that actually ran the handler, when the platform could attribute it. */
    private String executionNode;
    private boolean settlementStarted;
    /** Whether the end-to-end conclusion has already been recorded for this invocation. */
    private boolean metricsRecorded;

    public static ExecutionRecord withInputResources(
            String executionId,
            InvocationTask task,
            InvocationCapacity.Admission admission) {
        return new ExecutionRecord(executionId, task, TimeSource.system(),
                new ExecutionInputResources(admission));
    }

    ExecutionRecord(String executionId, InvocationTask task, TimeSource timeSource,
                    ExecutionInputResources inputResources) {
        this.executionId = executionId;
        this.task = task;
        this.timeSource = timeSource;
        this.inputResources = inputResources;
        this.ownership = new AttemptOwnership(inputResources.generation());
        boolean keyed = task.idempotencyKey() != null && !task.idempotencyKey().isBlank();
        this.readableAfterFinishing = task.kind() == InvocationKind.ASYNC || keyed;
        this.idempotencyKey = keyed ? task.idempotencyKey() : null;
        this.completion = new CompletableFuture<>();
        this.state = ExecutionState.QUEUED;
        this.admittedAt = task.enqueuedAt();
        this.admittedAtNanos = timeSource.nanoTime();
        this.attemptEnqueuedAtNanos = this.admittedAtNanos;
    }

    public String executionId() {
        return executionId;
    }

    /** The key the execution was admitted under, stable across internal retries; null if unkeyed. */
    public String idempotencyKey() {
        return idempotencyKey;
    }

    public CompletableFuture<InvocationResult> completion() {
        return completion;
    }

    // ---- Offload routing

    public synchronized void pinPlannedRoute(PlannedInvocationRoute route) {
        if (plannedRoute != null && !plannedRoute.equals(route)) {
            throw new IllegalStateException("execution route already pinned");
        }
        plannedRoute = route;
    }

    public synchronized PlannedInvocationRoute plannedRoute() {
        return plannedRoute;
    }

    public synchronized String executionNode() {
        return executionNode;
    }

    /** Attributes the execution to {@code node} while it is still live; a bounded, non-blank name only. */
    public synchronized void attributeExecutionNode(String node) {
        if (node != null && !node.isBlank() && node.length() <= 256 && !isTerminal()) {
            executionNode = node;
        }
    }

    // ---- Reads

    /**
     * Returns a consistent snapshot of the current execution state.
     * All fields are read atomically.
     */
    public synchronized Snapshot snapshot() {
        TerminalAnswer settled = answerOrNone();
        return new Snapshot(executionId, task, state, admittedAt, startedAt, finishedAt, dispatchedAt,
                settled.output(), settled.error(), coldStart, initDurationMs,
                settled.statusCode(), settled.headers(), settled.encoding());
    }

    /**
     * The outcome to archive, applying the reader rule.
     *
     * <p>If anyone can still ask for the result - an ASYNC caller, who holds nothing
     * but the execution's id, or a retry replaying an idempotency key - the outcome is
     * complete: {@code ReactiveInvocationCoordinator} reads {@code output} to serve that
     * replay, and serving it empty would be the double execution the key exists to
     * prevent.
     *
     * <p>Otherwise the payload is not retained at all. A synchronous caller without a
     * key has already received it in the body of the {@code :invoke} response: keeping a
     * second copy costs 4,916 bytes per record with a 4 KB response, against the 116 of
     * this outcome. The error is always kept - two strings - because it is the only thing
     * worth re-reading if the connection dropped before the body.
     *
     * <p>Reads the fields directly instead of going through {@link #snapshot()}: the
     * completion path allocates no Snapshot.
     */
    public synchronized Outcome toOutcome() {
        TerminalAnswer settled = answerOrNone();
        return new Outcome(
                state,
                Outcome.epochMilli(admittedAt),
                Outcome.epochMilli(finishedAt),
                readableAfterFinishing ? settled.output() : null,
                settled.error(),
                readableAfterFinishing ? settled.headers() : terminalExecutionHeaders(settled.headers()),
                readableAfterFinishing ? settled.encoding() : null,
                settled.statusCode() == null ? Outcome.NO_STATUS : settled.statusCode(),
                initDurationMs == null ? Outcome.NO_INIT : initDurationMs,
                coldStart,
                readableAfterFinishing,
                executionNode
        );
    }

    private static Map<String, String> terminalExecutionHeaders(Map<String, String> headers) {
        String id = headers == null ? null : headers.get("X-NanoFaaS-Terminal-Execution-Id");
        return id == null ? null : Map.of("X-NanoFaaS-Terminal-Execution-Id", id);
    }

    private TerminalAnswer answerOrNone() {
        return answer == null ? NO_ANSWER : answer;
    }

    // ---- State transitions

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

    /** The one terminal transition: the state, the instant it ended, and the answer it settled on. */
    private void terminate(ExecutionState target, TerminalAnswer settled) {
        if (!canTransition(target)) {
            return;
        }
        this.state = target;
        this.finishedAt = timeSource.instant();
        this.finishedAtNanos = timeSource.nanoTime();
        this.answer = settled;
    }

    public synchronized void markRunning() {
        if (!canTransition(ExecutionState.RUNNING)) {
            return;
        }
        this.state = ExecutionState.RUNNING;
        this.startedAt = timeSource.instant();
        this.startedAtNanos = timeSource.nanoTime();
    }

    public synchronized void markSuccess(Object output) {
        markSuccess(output, null, null, null);
    }

    /**
     * Marks the execution as completed with success, carrying the handler-decided
     * response envelope (status code, headers, encoding) through to the recorded result.
     */
    public synchronized void markSuccess(Object output, Integer statusCode,
                                          Map<String, String> headers, String encoding) {
        terminate(ExecutionState.SUCCESS, TerminalAnswer.success(output, statusCode, headers, encoding));
    }

    public synchronized void markError(ErrorInfo error) {
        markError(error, null);
    }

    /** Marks an error while retaining trusted headers from a terminal remote response. */
    public synchronized void markError(ErrorInfo error, Map<String, String> terminalHeaders) {
        markError(error, null, terminalHeaders);
    }

    public synchronized void markError(ErrorInfo error, Integer statusCode, Map<String, String> terminalHeaders) {
        terminate(ExecutionState.ERROR, TerminalAnswer.error(error, statusCode, terminalHeaders, null));
    }

    synchronized void markTimeout(ErrorInfo error) {
        terminate(ExecutionState.TIMEOUT, TerminalAnswer.error(error, null, null, null));
    }

    /** Choose the exceptional offload answer atomically with its terminal state. */
    public synchronized void markFailure(ErrorInfo error, Throwable failure) {
        markFailure(error, failure, null, null);
    }

    public synchronized void markFailure(ErrorInfo error, Throwable failure, Integer statusCode, Map<String, String> headers) {
        terminate(ExecutionState.ERROR, TerminalAnswer.error(error, statusCode, headers, failure));
    }

    /** Marks the dispatch time for queue wait calculation. */
    public synchronized void markDispatchedAt() {
        this.dispatchedAt = timeSource.instant();
    }

    /** Records cold start information from runtime headers. */
    public synchronized void markColdStart(long initDurationMs) {
        this.coldStart = true;
        this.initDurationMs = initDurationMs;
    }

    /** Resets the execution for a retry attempt. */
    public synchronized void resetForRetry(InvocationTask retryTask) {
        if (!canTransition(ExecutionState.QUEUED)) {
            return;
        }
        this.task = retryTask;
        this.state = ExecutionState.QUEUED;
        this.answer = null;
        this.startedAt = null;
        this.finishedAt = null;
        this.dispatchedAt = null;
        this.startedAtNanos = null;
        this.finishedAtNanos = null;
        // The retry is a fresh enqueue: its wait starts now, not at the original admission.
        this.attemptEnqueuedAtNanos = timeSource.nanoTime();
        this.coldStart = false;
        this.initDurationMs = null;
        ownership.resetForRetry();
    }

    // ---- The current attempt's transport (see AttemptOwnership)

    /**
     * The generation this logical execution was admitted under. Stable across retries and across the
     * lease's release: it fences a late completion's metrics against a function that has since been
     * removed and re-registered under a new identity (I7).
     */
    public synchronized FunctionGeneration currentGeneration() {
        return ownership.admittedGeneration();
    }

    /**
     * Registers the raw cancellable transport handle of the current attempt, as soon as the
     * dispatcher hands it back, so an administrative expiry that won the race against publication
     * still cancels it.
     */
    public void attachDispatchHandle(Future<?> handle) {
        boolean cancel;
        synchronized (this) {
            cancel = ownership.attachHandle(handle);
        }
        if (cancel) {
            handle.cancel(true);
        }
    }

    /** Requests cancellation of the current attempt, remembered even before its handle is attached. */
    // The wildcard is deliberate (java:S1452): handles have unrelated result types, callers only cancel.
    public synchronized Future<?> takeDispatchHandle() { // NOSONAR
        return ownership.requestCancellation();
    }

    public synchronized void transportOwnsCapacity() {
        ownership.transportOwnsCapacity();
    }

    public synchronized boolean capacityOwnedByTransport() {
        return ownership.capacityOwnedByTransport();
    }

    // ---- Settlement and input resources

    public boolean beginSettlement() {
        synchronized (this) {
            if (!isTerminal() || settlementStarted) return false;
            settlementStarted = true;
        }
        inputResources.settleLogicalExecution();
        return true;
    }

    /** Acquires one more same-generation reader of the retained input, for an attempt's transport. */
    public PhysicalInput openPhysicalInput(InvocationTask source) {
        return inputResources.open(source);
    }

    /** Adds a queue-entry input owner before the task is published to any queue. */
    public synchronized InvocationTask prepareForQueue() {
        task = task.withQueuedInputLease(inputResources.retainForQueue(task));
        return task;
    }

    public void publishAdmissionResources() {
        inputResources.publish();
    }

    public void rollbackAdmissionResources() {
        inputResources.rollback();
    }

    /** Publish only the selected terminal answer; callbacks run outside the monitor. */
    public void publishTerminal() {
        InvocationResult result;
        Throwable failure;
        synchronized (this) {
            if (!isTerminal()) return;
            failure = answer.failure();
            result = answer.toResult(state);
        }
        if (failure != null) completion.completeExceptionally(failure);
        else completion.complete(result);
    }

    // ---- Field reads, each under the monitor; prefer snapshot() for several at once

    public synchronized InvocationTask task() {
        return task;
    }

    public synchronized ExecutionState state() {
        return state;
    }

    /** Monotonic admission instant, for end-to-end duration computation. */
    public synchronized long admittedAtNanos() {
        return admittedAtNanos;
    }

    /** Monotonic enqueue instant of the current attempt, for its wait computation. */
    public synchronized long attemptEnqueuedAtNanos() {
        return attemptEnqueuedAtNanos;
    }

    public synchronized Long startedAtNanos() {
        return startedAtNanos;
    }

    public synchronized Long finishedAtNanos() {
        return finishedAtNanos;
    }

    /**
     * Wall-clock now from this record's own {@link TimeSource}. Collaborators that stamp
     * something onto the record (a retry task's enqueue instant) read the clock here rather than
     * calling {@code Instant.now()}, so a steered clock stays consistent across the whole record.
     */
    public Instant now() {
        return timeSource.instant();
    }

    public synchronized boolean markMetricsRecorded() {
        if (metricsRecorded) {
            return false;
        }
        metricsRecorded = true;
        return true;
    }

    public synchronized boolean isTerminal() {
        return isTerminalState(state);
    }

    public synchronized ErrorInfo lastError() {
        return answerOrNone().error();
    }

    /**
     * Immutable snapshot of execution state for consistent reads.
     */
    public record Snapshot(
            String executionId,
            InvocationTask task,
            ExecutionState state,
            Instant admittedAt,
            Instant startedAt,
            Instant finishedAt,
            Instant dispatchedAt,
            Object output,
            ErrorInfo lastError,
            boolean coldStart,
            Long initDurationMs,
            Integer statusCode,
            Map<String, String> headers,
            String encoding
    ) {}
}
