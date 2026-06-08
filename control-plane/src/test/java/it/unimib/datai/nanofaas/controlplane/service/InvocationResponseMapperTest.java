package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
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
        ExecutionRecord record = new ExecutionRecord("exec-1", task("exec-1"));
        record.markTimeout();

        InvocationResponse response = mapper.terminalResponse(record);

        assertThat(response.status()).isEqualTo("timeout");
        assertThat(response.executionId()).isEqualTo("exec-1");
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
