package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.common.model.ErrorInfo;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacity;
import it.unimib.datai.nanofaas.controlplane.input.CanonicalInvocationInput;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mutable execution record with thread-safe state transitions.
 *
 * State transitions are synchronized to ensure consistency between related fields.
 * Use {@link #snapshot()} to get a consistent view of all fields.
 */
public class ExecutionRecord {
    private static final Logger log = LoggerFactory.getLogger(ExecutionRecord.class);

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
    private Integer statusCode;
    private Map<String, String> headers;
    private String encoding;
    private it.unimib.datai.nanofaas.controlplane.offload.PlannedInvocationRoute plannedRoute;
    private String executionNode;
    public synchronized void pinPlannedRoute(it.unimib.datai.nanofaas.controlplane.offload.PlannedInvocationRoute route) {
        if(plannedRoute!=null && !plannedRoute.equals(route)) throw new IllegalStateException("execution route already pinned");plannedRoute=route;
    }
    public synchronized it.unimib.datai.nanofaas.controlplane.offload.PlannedInvocationRoute plannedRoute() { return plannedRoute; }
    public synchronized String executionNode() { return executionNode; }
    public synchronized void attributeExecutionNode(String node) {
        if(node!=null && !node.isBlank() && node.length()<=256 && !isTerminal()) executionNode=node;
    }


    /**
     * The capacity lease the current attempt owns, set at dispatch and released exactly once
     * by the attempt's completion (invariant I4). Null for a path that never acquired local
     * capacity (offload). Physical dispatch, once started, owns the release.
     */
    private DispatchOwnership dispatchLease;
    /**
     * The generation the current attempt was admitted under, captured alongside the lease but
     * kept even after {@link #takeDispatchLease()} detaches it (unlike a plain peek at
     * {@code dispatchLease}, which would go stale the moment that happens). A real
     * dispatch marks {@link #transportOwnsCapacity()}, which makes {@code releaseAttemptCapacity}
     * skip it entirely — but a caller of {@link #currentGeneration()} should not have to know
     * that to trust the answer (I7). Logical admission identity remains stable across retries.
     */
    private FunctionGeneration admittedGeneration;

    /**
     * The real cancellable transport handle of the current attempt (the raw dispatch future,
     * not a composed one). Administrative expiry requests local cancellation on it so the
     * work (and its payload) does not outlive the released capacity.
     */
    private Future<?> dispatchHandle;
    private boolean dispatchCancellationRequested;
    private boolean transportOwnsCapacity;
    private Throwable terminalFailure;
    private boolean settlementStarted;

    private final TimeSource timeSource;

    /**
     * The instant the caller was admitted, and its monotonic counterpart. Deliberately final and
     * separate from {@code task.enqueuedAt()}: a retry replaces the task (and with it the enqueue
     * instant of the current attempt), but the invocation began here and its end-to-end duration
     * must be measured from here, retries included.
     */
    private final Instant admittedAt;
    private final long admittedAtNanos;

    /** Monotonic enqueue instant of the current attempt (the first attempt is the admission itself). */
    private long attemptEnqueuedAtNanos;
    /** Monotonic dispatch/completion instants of the current attempt. */
    private Long startedAtNanos;
    private Long finishedAtNanos;

    /** Whether the end-to-end conclusion has already been recorded for this invocation. */
    private boolean metricsRecorded;

    public ExecutionRecord(String executionId, InvocationTask task) {
        this(executionId, task, TimeSource.system(), null);
    }

    public ExecutionRecord(String executionId, InvocationTask task, TimeSource timeSource) {
        this(executionId, task, timeSource, null);
    }

    public static ExecutionRecord withInputResources(
            String executionId,
            InvocationTask task,
            InvocationCapacity capacity,
            InvocationCapacity.Admission admission,
            CanonicalInvocationInput.Accepted canonical) {
        return new ExecutionRecord(executionId, task, TimeSource.system(),
                new ExecutionInputResources(capacity, admission, canonical));
    }

    ExecutionRecord(String executionId, InvocationTask task, TimeSource timeSource,
                    ExecutionInputResources inputResources) {
        this.executionId = executionId;
        this.task = task;
        this.timeSource = timeSource;
        this.inputResources = inputResources;
        this.admittedGeneration = inputResources == null ? null : inputResources.generation();
        this.readableAfterFinishing = task.kind() == InvocationKind.ASYNC
                || (task.idempotencyKey() != null && !task.idempotencyKey().isBlank());
        this.idempotencyKey = (task.idempotencyKey() != null && !task.idempotencyKey().isBlank())
                ? task.idempotencyKey()
                : null;
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

    /**
     * Returns a consistent snapshot of the current execution state.
     * All fields are read atomically.
     */
    public synchronized Snapshot snapshot() {
        return new Snapshot(
                executionId,
                task,
                state,
                admittedAt,
                startedAt,
                finishedAt,
                dispatchedAt,
                output,
                lastError,
                coldStart,
                initDurationMs,
                statusCode,
                headers,
                encoding
        );
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
     * completion path allocates no Snapshot, and there is no reason to start now only to
     * throw it away immediately afterwards.
     */
    public synchronized Outcome toOutcome() {
        return new Outcome(
                state,
                Outcome.epochMilli(admittedAt),
                Outcome.epochMilli(finishedAt),
                readableAfterFinishing ? output : null,
                lastError,
                readableAfterFinishing ? headers : terminalExecutionHeaders(),
                readableAfterFinishing ? encoding : null,
                statusCode == null ? Outcome.NO_STATUS : statusCode,
                initDurationMs == null ? Outcome.NO_INIT : initDurationMs,
                coldStart,
                readableAfterFinishing,
                executionNode
        );
    }

    private Map<String, String> terminalExecutionHeaders() {
        String id = headers == null ? null : headers.get("X-NanoFaaS-Terminal-Execution-Id");
        return id == null ? null : Map.of("X-NanoFaaS-Terminal-Execution-Id", id);
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
        this.startedAt = timeSource.instant();
        this.startedAtNanos = timeSource.nanoTime();
    }

    /**
     * Marks the execution as completed with success.
     */
    public synchronized void markSuccess(Object output) {
        markSuccess(output, null, null, null);
    }

    /**
     * Marks the execution as completed with success, carrying the handler-decided
     * response envelope (status code, headers, encoding) through to the recorded result.
     */
    public synchronized void markSuccess(Object output, Integer statusCode,
                                          Map<String, String> headers, String encoding) {
        if (!canTransition(ExecutionState.SUCCESS)) {
            return;
        }
        if(plannedRoute!=null && plannedRoute.kind()==it.unimib.datai.nanofaas.controlplane.offload.PlannedInvocationRoute.Kind.LOCAL) executionNode=plannedRoute.executionNode();
        this.state = ExecutionState.SUCCESS;
        this.finishedAt = timeSource.instant();
        this.finishedAtNanos = timeSource.nanoTime();
        this.output = output;
        this.lastError = null;
        this.statusCode = statusCode;
        this.headers = headers;
        this.encoding = encoding;
    }

    /**
     * Marks the execution as completed with error.
     */
    public synchronized void markError(ErrorInfo error) {
        markError(error, null);
    }

    /** Marks an error while retaining trusted headers from a terminal remote response. */
    public synchronized void markError(ErrorInfo error, Map<String, String> terminalHeaders) {
        if (!canTransition(ExecutionState.ERROR)) {
            return;
        }
        this.state = ExecutionState.ERROR;
        this.finishedAt = timeSource.instant();
        this.finishedAtNanos = timeSource.nanoTime();
        this.lastError = error;
        this.output = null;
        this.headers = terminalHeaders;
    }

    /**
     * Marks the execution as timed out.
     */
    public synchronized void markTimeout() {
        markTimeout(null);
    }

    synchronized void markTimeout(ErrorInfo error) {
        if (!canTransition(ExecutionState.TIMEOUT)) {
            return;
        }
        this.state = ExecutionState.TIMEOUT;
        this.lastError = error;
        this.finishedAt = timeSource.instant();
        this.finishedAtNanos = timeSource.nanoTime();
    }

    /**
     * Marks the dispatch time for queue wait calculation.
     */
    public synchronized void markDispatchedAt() {
        this.dispatchedAt = timeSource.instant();
    }

    /**
     * Records cold start information from runtime headers.
     */
    public synchronized void markColdStart(long initDurationMs) {
        this.coldStart = true;
        this.initDurationMs = initDurationMs;
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
        this.startedAtNanos = null;
        this.finishedAtNanos = null;
        // The retry is a fresh enqueue: its wait starts now, not at the original admission.
        this.attemptEnqueuedAtNanos = timeSource.nanoTime();
        this.lastError = null;
        this.output = null;
        this.coldStart = false;
        this.initDurationMs = null;
        // ponytail: statusCode/headers/encoding are only ever set by markSuccess(),
        // which makes the state terminal, and canTransition() above already refuses
        // this method on a terminal record — so today, these three are always still
        // null here. Kept as defensive insurance against a future change that lets
        // the envelope be set outside markSuccess(); no test can exercise them
        // going from non-null to null without breaking that invariant.
        this.statusCode = null;
        this.headers = null;
        this.encoding = null;
        // The failed attempt's lease and handle were released by its completion; a retry is a
        // fresh attempt that re-acquires at its own dispatch. Cleared here so a stale handle
        // from the previous attempt can never be cancelled on the next attempt's behalf.
        this.dispatchLease = null;
        this.dispatchHandle = null;
        this.dispatchCancellationRequested = false;
        this.transportOwnsCapacity = false;
    }

    /**
     * Records the lease the current attempt acquired at dispatch. Called under the record
     * monitor, before the dispatch future is kicked off.
     */
    public synchronized void attachDispatchLease(DispatchOwnership lease) {
        this.dispatchLease = lease;
        if (this.admittedGeneration == null) {
            this.admittedGeneration = lease.generation();
        }
    }

    /**
     * Detaches and returns the current attempt's lease, or {@code null} when this attempt
     * never acquired one. The caller releases the returned lease (idempotently) outside the
     * monitor where possible.
     */
    public synchronized DispatchOwnership takeDispatchLease() {
        DispatchOwnership lease = dispatchLease;
        dispatchLease = null;
        return lease;
    }

    /** Whether the current attempt holds a lease it has not yet released. */
    public synchronized boolean holdsDispatchLease() {
        return dispatchLease != null;
    }

    /**
     * The generation this logical execution was admitted under, or {@code null} only for
     * compatibility records built without aggregate input resources. A dispatch lease may
     * reaffirm the same identity for a local attempt; offload retains the admission identity.
     * Stable across
     * {@link #takeDispatchLease()}: a listener that fires after release accounting has already
     * detached the lease (e.g. the administrative-expiry path's terminal-conclusion timer) still
     * needs to fence against the SAME generation the attempt was admitted under, not "whatever
     * happens to still be on the lease field". Used to fence a late completion's metrics against
     * a function that has since been removed and re-registered under a new identity (I7).
     */
    public synchronized FunctionGeneration currentGeneration() {
        return admittedGeneration;
    }

    /**
     * Registers the raw cancellable transport handle of the current attempt. Called as soon
     * as the dispatcher hands the future back, so an administrative expiry that wins the race
     * against publication can still cancel it.
     */
    public void attachDispatchHandle(Future<?> handle) {
        boolean cancel;
        synchronized (this) {
            cancel = dispatchCancellationRequested;
            if (!cancel) dispatchHandle = handle;
        }
        if (cancel) handle.cancel(true);
    }

    /** Remember the request even when the dispatcher has not returned its handle yet. */
    // The wildcard is deliberate (java:S1452): handles have unrelated result types, callers only cancel.
    public synchronized Future<?> takeDispatchHandle() { // NOSONAR
        dispatchCancellationRequested = true;
        Future<?> handle = dispatchHandle;
        dispatchHandle = null;
        return handle;
    }

    public synchronized void transportOwnsCapacity() {
        transportOwnsCapacity = true;
    }

    public synchronized boolean capacityOwnedByTransport() {
        return transportOwnsCapacity;
    }

    /** Choose the exceptional offload answer atomically with its terminal state. */
    public synchronized void markFailure(ErrorInfo error, Throwable failure) {
        if (isTerminal()) return;
        markError(error);
        terminalFailure = failure;
    }

    public boolean beginSettlement() {
        synchronized (this) {
            if (!isTerminal() || settlementStarted) return false;
            settlementStarted = true;
        }
        if (inputResources != null) inputResources.settleLogicalExecution();
        return true;
    }

    /** Acquires the same-generation canonical-input reader and any real materialized copy. */
    public PhysicalInput openPhysicalInput(InvocationTask source) {
        if (inputResources == null) return new PhysicalInput(source, null, null);
        return inputResources.open(source);
    }

    /** Adds a queue-entry input owner before the task is published to any queue. */
    public synchronized InvocationTask prepareForQueue() {
        if (inputResources == null) return task;
        task = task.withQueuedInputLease(inputResources.retainForQueue(task));
        return task;
    }

    public void publishAdmissionResources() {
        if (inputResources != null) inputResources.publish();
    }

    public void rollbackAdmissionResources() {
        if (inputResources != null) inputResources.rollback();
    }

    public static final class PhysicalInput implements AutoCloseable {
        private final InvocationTask task;
        private final AutoCloseable sharedReference;
        private final AutoCloseable copyReservation;
        private final AtomicBoolean closed = new AtomicBoolean();

        PhysicalInput(InvocationTask task, AutoCloseable sharedReference, AutoCloseable copyReservation) {
            this.task = task;
            this.sharedReference = sharedReference;
            this.copyReservation = copyReservation;
        }

        public InvocationTask task() {
            return task;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try {
                closeUnchecked(copyReservation);
            } finally {
                closeUnchecked(sharedReference);
            }
        }

        private static void closeUnchecked(AutoCloseable closeable) {
            if (closeable == null) return;
            try {
                closeable.close();
            } catch (RuntimeException | Error failure) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
                throw failure;
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    /** Publish only the selected terminal answer; callbacks run outside the monitor. */
    public void publishTerminal() {
        InvocationResult result;
        Throwable failure;
        synchronized (this) {
            if (!isTerminal()) return;
            failure = terminalFailure;
            if (state == ExecutionState.SUCCESS) {
                result = InvocationResult.successWithEnvelope(output, statusCode, headers, encoding);
            } else {
                ErrorInfo error = lastError != null ? lastError : new ErrorInfo("TIMEOUT", "Execution timed out");
                result = new InvocationResult(false, null, error, null,
                        terminalExecutionHeaders(), null);
            }
        }
        if (failure != null) completion.completeExceptionally(failure);
        else completion.complete(result);
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

    /**
     * The instant the caller was admitted. Survives retries: {@link #startedAt()} is the current
     * attempt's dispatch instant, while this is the invocation's true start.
     */
    public synchronized Instant admittedAt() {
        return admittedAt;
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
