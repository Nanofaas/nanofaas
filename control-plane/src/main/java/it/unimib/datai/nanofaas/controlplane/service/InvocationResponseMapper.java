package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionStatus;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import org.springframework.stereotype.Service;

@Service
public final class InvocationResponseMapper {

    public InvocationResponse toResponse(ExecutionRecord record, InvocationResult result) {
        String status = result.success() ? "success" : "error";
        return new InvocationResponse(record.executionId(), status, result.output(), result.error());
    }

    public InvocationResponse timeoutResponse(ExecutionRecord record) {
        return new InvocationResponse(record.executionId(), "timeout", null, null);
    }

    public InvocationResponse terminalResponse(ExecutionRecord record) {
        ExecutionRecord.Snapshot snapshot = record.snapshot();
        if (snapshot.state() == ExecutionState.SUCCESS || snapshot.state() == ExecutionState.ERROR) {
            InvocationResult result = snapshot.lastError() == null
                    ? InvocationResult.success(snapshot.output())
                    : new InvocationResult(false, null, snapshot.lastError());
            return toResponse(record, result);
        }
        if (snapshot.state() == ExecutionState.TIMEOUT) {
            return timeoutResponse(record);
        }
        return null;
    }

    public ExecutionStatus toStatus(ExecutionRecord record) {
        ExecutionRecord.Snapshot snapshot = record.snapshot();
        String status = snapshot.state().name().toLowerCase();
        return new ExecutionStatus(
                snapshot.executionId(),
                status,
                snapshot.startedAt(),
                snapshot.finishedAt(),
                snapshot.output(),
                snapshot.lastError(),
                snapshot.coldStart(),
                snapshot.initDurationMs()
        );
    }
}
