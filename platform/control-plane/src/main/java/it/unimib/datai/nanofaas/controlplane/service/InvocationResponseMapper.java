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
                snapshot.admittedAt(),
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
     * The idempotent replay served from an archived outcome.
     *
     * <p>Only a {@code readable} outcome gets here: it is the key itself that makes it
     * readable, and {@link Outcome} retains the payload for exactly this case.
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
     * The status of a finished execution.
     *
     * <p>For a keyless synchronous execution {@code output} is {@code null}: that body
     * already went back to the caller on its own connection, and the schema declares the
     * field optional with {@code null} among the admitted types.
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
