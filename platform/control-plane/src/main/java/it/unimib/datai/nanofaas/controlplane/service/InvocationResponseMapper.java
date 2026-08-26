package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionStatus;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.Outcome;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public final class InvocationResponseMapper {

    public InvocationResponse toResponse(ExecutionRecord executionRecord, InvocationResult result) {
        String status = result.success() ? "success" : "error";
        return new InvocationResponse(executionRecord.executionId(), status, result.output(), result.error(),
                result.statusCode(), result.headers(), result.encoding());
    }

    public InvocationResponse timeoutResponse(ExecutionRecord executionRecord) {
        return new InvocationResponse(executionRecord.executionId(), "timeout", null, null);
    }

    public InvocationResponse terminalResponse(ExecutionRecord executionRecord) {
        ExecutionRecord.Snapshot snapshot = executionRecord.snapshot();
        if (snapshot.state() == ExecutionState.SUCCESS || snapshot.state() == ExecutionState.ERROR) {
            InvocationResult result = snapshot.lastError() == null
                    ? InvocationResult.successWithEnvelope(snapshot.output(), snapshot.statusCode(),
                            snapshot.headers(), snapshot.encoding())
                    : new InvocationResult(false, null, snapshot.lastError());
            return toResponse(executionRecord, result);
        }
        if (snapshot.state() == ExecutionState.TIMEOUT) {
            return timeoutResponse(executionRecord);
        }
        return null;
    }

    public ExecutionStatus toStatus(ExecutionRecord executionRecord) {
        ExecutionRecord.Snapshot snapshot = executionRecord.snapshot();
        // Locale.ROOT, not the default locale: RUNNING and TIMEOUT contain 'I', which folds to
        // dotless 'ı' under a Turkish/Azerbaijani default — this string is the public status field.
        String status = snapshot.state().name().toLowerCase(Locale.ROOT);
        return new ExecutionStatus(
                snapshot.executionId(),
                status,
                snapshot.startedAt(),
                snapshot.finishedAt(),
                snapshot.output(),
                snapshot.lastError(),
                snapshot.coldStart(),
                snapshot.initDurationMs(),
                snapshot.statusCode(),
                snapshot.headers(),
                snapshot.encoding()
        );
    }

    /**
     * Il replay idempotente servito da un esito archiviato.
     *
     * <p>Arriva qui solo un esito {@code readable}: e' proprio la chiave a renderlo
     * tale, e {@link Outcome} trattiene il payload esattamente per questo caso.
     */
    public InvocationResponse terminalResponse(String executionId, Outcome outcome) {
        if (outcome.state() == ExecutionState.TIMEOUT) {
            return new InvocationResponse(executionId, "timeout", null, null);
        }
        String status = outcome.error() == null ? "success" : "error";
        return new InvocationResponse(executionId, status, outcome.output(), outcome.error(),
                outcome.statusCodeOrNull(), outcome.headers(), outcome.encoding());
    }

    /**
     * Lo stato di un'esecuzione finita.
     *
     * <p>Per un'esecuzione sincrona senza chiave {@code output} e' {@code null}:
     * quel corpo e' gia' tornato al chiamante sulla sua connessione, e lo schema
     * lo dichiara facoltativo con {@code null} fra i tipi ammessi.
     */
    public ExecutionStatus toStatus(String executionId, Outcome outcome) {
        return new ExecutionStatus(
                executionId,
                outcome.state().name().toLowerCase(Locale.ROOT),
                outcome.startedAt(),
                outcome.finishedAt(),
                outcome.output(),
                outcome.error(),
                outcome.coldStart(),
                outcome.initDurationMsOrNull(),
                outcome.statusCodeOrNull(),
                outcome.headers(),
                outcome.encoding()
        );
    }
}
