package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.common.model.ErrorInfo;

import java.time.Instant;
import java.util.Map;

/**
 * What is left of a finished execution, and nothing else.
 *
 * <p>A terminal {@link ExecutionRecord} retains 35 objects and 1,330 bytes (measured
 * on 2026-08-26 over 200,000 records): the future, the task, the request, five
 * {@link Instant}s, a {@code HashSet} to hold {@code {0}}. Only one is needed, and for
 * only two reasons: answering {@code GET /v1/executions/{id}} and serving the replay of
 * an idempotency key. This record is exactly those two reasons: 4 objects, 116 bytes.
 *
 * <p>The times are {@code long} and not {@link Instant} because five objects per record,
 * multiplied by the hundreds of thousands the store keeps in flight, are half its weight.
 * Zero means absent: no execution really begins at the epoch.
 */
public record Outcome(
        ExecutionState state,
        long startedAtMs,
        long finishedAtMs,
        Object output,
        ErrorInfo error,
        Map<String, String> headers,
        String encoding,
        int statusCode,
        long initDurationMs,
        boolean coldStart,
        boolean readable,
        String executionNode
) {
    public Outcome(ExecutionState state,long started,long finished,Object output,ErrorInfo error,Map<String,String> headers,String encoding,int status,long init,boolean cold,boolean readable) {
        this(state,started,finished,output,error,headers,encoding,status,init,cold,readable,null);
    }
    /** No HTTP status is 0, and no execution begins at the epoch. */
    static final int NO_STATUS = 0;
    static final long ABSENT = 0L;
    /** -1, not 0: a 0 ms init is a legitimate value. */
    static final long NO_INIT = -1L;

    static long epochMilli(Instant instant) {
        return instant == null ? ABSENT : instant.toEpochMilli();
    }

    public Instant startedAt() {
        return startedAtMs == ABSENT ? null : Instant.ofEpochMilli(startedAtMs);
    }

    public Instant finishedAt() {
        return finishedAtMs == ABSENT ? null : Instant.ofEpochMilli(finishedAtMs);
    }

    public Integer statusCodeOrNull() {
        return statusCode == NO_STATUS ? null : statusCode;
    }

    public Long initDurationMsOrNull() {
        return initDurationMs == NO_INIT ? null : initDurationMs;
    }
}
