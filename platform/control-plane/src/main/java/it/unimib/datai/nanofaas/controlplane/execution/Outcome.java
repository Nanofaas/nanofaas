package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.common.model.ErrorInfo;

import java.time.Instant;
import java.util.Map;

/**
 * Cio' che resta di un'esecuzione finita, e nient'altro.
 *
 * <p>Un {@link ExecutionRecord} terminale trattiene 35 oggetti e 1.330 byte
 * (misurati il 2026-08-26 su 200.000 record): la future, il task, la richiesta,
 * cinque {@link Instant}, un {@code HashSet} per contenere {@code {0}}. Ne serve
 * uno solo, e per due soli motivi: rispondere a {@code GET /v1/executions/{id}} e
 * servire il replay di una chiave di idempotenza. Questo record e' esattamente
 * quei due motivi: 4 oggetti, 116 byte.
 *
 * <p>I tempi sono {@code long} e non {@code Instant} perche' cinque oggetti per
 * record, moltiplicati per le centinaia di migliaia che lo store tiene in volo,
 * sono la meta' del suo peso. Zero significa assente: nessuna esecuzione comincia
 * davvero all'epoch.
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
        boolean readable
) {
    /** Nessuno status HTTP e' 0, e nessuna esecuzione inizia all'epoch. */
    static final int NO_STATUS = 0;
    static final long ABSENT = 0L;
    /** -1, non 0: una init da 0 ms e' un valore legittimo. */
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
