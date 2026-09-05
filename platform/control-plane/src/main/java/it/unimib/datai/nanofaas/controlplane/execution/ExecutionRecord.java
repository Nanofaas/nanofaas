package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.common.model.ErrorInfo;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
    private final Set<Integer> releasedDispatchAttempts = new HashSet<>();

    public ExecutionRecord(String executionId, InvocationTask task) {
        this.executionId = executionId;
        this.task = task;
        this.readableAfterFinishing = task.kind() == InvocationKind.ASYNC
                || (task.idempotencyKey() != null && !task.idempotencyKey().isBlank());
        this.idempotencyKey = (task.idempotencyKey() != null && !task.idempotencyKey().isBlank())
                ? task.idempotencyKey()
                : null;
        this.completion = new CompletableFuture<>();
        this.state = ExecutionState.QUEUED;
    }

    public String executionId() {
        return executionId;
    }

    /** See {@link #readableAfterFinishing}. */
    public boolean readableAfterFinishing() {
        return readableAfterFinishing;
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
     * L'esito da archiviare, applicando la regola del lettore.
     *
     * <p>Se qualcuno puo' ancora chiedere il risultato - un chiamante ASYNC, che
     * dell'esecuzione ha solo l'id, o un retry che replica una chiave di
     * idempotenza - l'esito e' completo: {@code ReactiveInvocationCoordinator}
     * legge {@code output} per servire quel replay, e servirlo vuoto sarebbe la
     * doppia esecuzione che la chiave esiste per impedire.
     *
     * <p>Altrimenti il payload non viene trattenuto affatto. Un chiamante sincrono
     * senza chiave l'ha gia' ricevuto nel corpo della risposta di {@code :invoke}:
     * tenerne una seconda copia costa 4.916 byte per record con una risposta da
     * 4 KB, contro i 116 di questo esito. L'errore resta sempre - due stringhe -
     * perche' e' l'unica cosa che ha senso rileggere se la connessione e' caduta
     * prima del corpo.
     *
     * <p>Legge i campi direttamente invece di passare da {@link #snapshot()}: il
     * percorso di completamento non alloca uno Snapshot, e non e' il caso di
     * cominciare adesso per poi buttarlo via subito dopo.
     */
    public synchronized Outcome toOutcome() {
        return new Outcome(
                state,
                Outcome.epochMilli(startedAt),
                Outcome.epochMilli(finishedAt),
                readableAfterFinishing ? output : null,
                lastError,
                readableAfterFinishing ? headers : null,
                readableAfterFinishing ? encoding : null,
                statusCode == null ? Outcome.NO_STATUS : statusCode,
                initDurationMs == null ? Outcome.NO_INIT : initDurationMs,
                coldStart,
                readableAfterFinishing
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
        this.state = ExecutionState.SUCCESS;
        this.finishedAt = Instant.now();
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
        // ponytail: statusCode/headers/encoding are only ever set by markSuccess(),
        // which makes the state terminal, and canTransition() above already refuses
        // this method on a terminal record — so today, these three are always still
        // null here. Kept as defensive insurance against a future change that lets
        // the envelope be set outside markSuccess(); no test can exercise them
        // going from non-null to null without breaking that invariant.
        this.statusCode = null;
        this.headers = null;
        this.encoding = null;
    }

    /**
     * Records that the dispatch slot for the given attempt has been released.
     * @return true the first time this attempt is released, false on duplicates
     */
    public synchronized boolean markDispatchSlotReleased(int attempt) {
        return releasedDispatchAttempts.add(attempt);
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
            Long initDurationMs,
            Integer statusCode,
            Map<String, String> headers,
            String encoding
    ) {}
}
