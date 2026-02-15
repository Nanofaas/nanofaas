package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.common.model.ErrorInfo;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionRecordLegacyAccessorsTest {

    @Test
    void legacyMutators_updateFieldsConsistently() {
        InvocationTask originalTask = new InvocationTask("exec-1", "fn", null, null, null, null, null, 1);
        ExecutionRecord record = new ExecutionRecord("exec-1", originalTask);

        InvocationTask updatedTask = new InvocationTask("exec-1", "fn", null, null, null, null, null, 2);
        Instant startedAt = Instant.now().minusSeconds(5);
        Instant finishedAt = Instant.now();
        ErrorInfo error = new ErrorInfo("E", "boom");

        record.updateTask(updatedTask);
        record.state(ExecutionState.RUNNING);
        record.startedAt(startedAt);
        record.output("payload");
        record.lastError(error);
        record.finishedAt(finishedAt);

        assertThat(record.task()).isEqualTo(updatedTask);
        assertThat(record.state()).isEqualTo(ExecutionState.RUNNING);
        assertThat(record.startedAt()).isEqualTo(startedAt);
        assertThat(record.finishedAt()).isEqualTo(finishedAt);
        assertThat(record.output()).isEqualTo("payload");
        assertThat(record.lastError()).isEqualTo(error);
    }

    @Test
    void markSuccess_clearsError_andMarkError_clearsOutput() {
        ExecutionRecord record = new ExecutionRecord(
                "exec-2",
                new InvocationTask("exec-2", "fn", null, null, null, null, null, 1)
        );

        record.markRunning();
        record.markError(new ErrorInfo("ERR", "first"));
        assertThat(record.lastError()).isNotNull();
        assertThat(record.output()).isNull();

        record.markSuccess("ok");
        assertThat(record.output()).isEqualTo("ok");
        assertThat(record.lastError()).isNull();

        record.markError(new ErrorInfo("ERR2", "second"));
        assertThat(record.output()).isNull();
        assertThat(record.lastError().code()).isEqualTo("ERR2");
    }
}
