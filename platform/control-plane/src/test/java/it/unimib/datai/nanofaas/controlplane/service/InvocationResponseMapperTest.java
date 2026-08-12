package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.ExecutionStatus;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InvocationResponseMapperTest {

    private final InvocationResponseMapper mapper = new InvocationResponseMapper();

    @Test
    void terminalResponse_mapsTimeoutRecordToTimeoutResponse() {
        ExecutionRecord executionRecord = new ExecutionRecord("exec-1", task("exec-1"));
        executionRecord.markTimeout();

        InvocationResponse response = mapper.terminalResponse(executionRecord);

        assertThat(response.status()).isEqualTo("timeout");
        assertThat(response.executionId()).isEqualTo("exec-1");
    }

    @Test
    void terminalResponse_regression_plainOutput_hasNullEnvelope() {
        // Backward compatibility: a handler that returns a plain value (no envelope)
        // must produce exactly today's response on the replay path.
        ExecutionRecord executionRecord = new ExecutionRecord("exec-1", task("exec-1"));
        executionRecord.markSuccess("plain-body");

        InvocationResponse response = mapper.terminalResponse(executionRecord);

        assertThat(response.output()).isEqualTo("plain-body");
        assertThat(response.statusCode()).isNull();
        assertThat(response.headers()).isNull();
        assertThat(response.encoding()).isNull();
    }

    @Test
    void toResponse_propagatesStatusCodeAndHeadersFromResult() {
        ExecutionRecord executionRecord = new ExecutionRecord("exec-1", task("exec-1"));
        InvocationResult result = InvocationResult.successWithEnvelope(
                "body", 404, Map.of("Content-Type", "application/json"), null);

        InvocationResponse response = mapper.toResponse(executionRecord, result);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers()).containsEntry("Content-Type", "application/json");
    }

    @Test
    void toResponse_regression_plainResult_hasNullEnvelope() {
        ExecutionRecord executionRecord = new ExecutionRecord("exec-1", task("exec-1"));
        InvocationResult result = InvocationResult.success("body");

        InvocationResponse response = mapper.toResponse(executionRecord, result);

        assertThat(response.statusCode()).isNull();
        assertThat(response.headers()).isNull();
        assertThat(response.encoding()).isNull();
    }

    @Test
    void toStatus_propagatesStatusCodeAndHeadersFromSnapshot() {
        ExecutionRecord executionRecord = new ExecutionRecord("exec-1", task("exec-1"));
        executionRecord.markSuccess("body", 201, Map.of("Location", "/x"), "base64");

        ExecutionStatus status = mapper.toStatus(executionRecord);

        assertThat(status.statusCode()).isEqualTo(201);
        assertThat(status.headers()).containsEntry("Location", "/x");
        assertThat(status.encoding()).isEqualTo("base64");
    }

    @Test
    void toStatus_regression_plainSuccess_hasNullEnvelope() {
        ExecutionRecord executionRecord = new ExecutionRecord("exec-1", task("exec-1"));
        executionRecord.markSuccess("body");

        ExecutionStatus status = mapper.toStatus(executionRecord);

        assertThat(status.statusCode()).isNull();
        assertThat(status.headers()).isNull();
        assertThat(status.encoding()).isNull();
    }

    @Test
    void terminalResponse_propagatesEncoding() {
        // Guards the field that is easiest to drop: terminalResponse rebuilds an
        // InvocationResult from the snapshot, so encoding must come from the snapshot,
        // not be hardcoded null.
        ExecutionRecord executionRecord = new ExecutionRecord("exec-1", task("exec-1"));
        executionRecord.markSuccess("body", 200, Map.of(), "base64");

        InvocationResponse response = mapper.terminalResponse(executionRecord);

        assertThat(response.encoding()).isEqualTo("base64");
    }

    private static InvocationTask task(String executionId) {
        FunctionSpec spec = new FunctionSpec(
                "fn",
                "image",
                null,
                Map.of(),
                null,
                1000,
                1,
                10,
                1,
                null,
                ExecutionMode.LOCAL,
                null,
                null,
                null
        );
        return new InvocationTask(
                executionId,
                spec.name(),
                spec,
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                Instant.now(),
                1
        );
    }
}
