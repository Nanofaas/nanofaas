package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionStatus;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
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
}
