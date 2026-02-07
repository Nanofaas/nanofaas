package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.common.model.ErrorInfo;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionRecordStateTransitionTest {

    @Test
    void validTransition_queued_to_running() {
        ExecutionRecord record = createRecord("exec-1");
        assertThat(record.state()).isEqualTo(ExecutionState.QUEUED);

        record.markRunning();
        assertThat(record.state()).isEqualTo(ExecutionState.RUNNING);
    }

    @Test
    void validTransition_running_to_success() {
        ExecutionRecord record = createRecord("exec-1");
        record.markRunning();

        record.markSuccess("output");
        assertThat(record.state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(record.output()).isEqualTo("output");
    }

    @Test
    void validTransition_running_to_error() {
        ExecutionRecord record = createRecord("exec-1");
        record.markRunning();

        ErrorInfo error = new ErrorInfo("TEST_ERROR", "something failed");
        record.markError(error);
        assertThat(record.state()).isEqualTo(ExecutionState.ERROR);
        assertThat(record.lastError()).isEqualTo(error);
    }

    @Test
    void validTransition_running_to_timeout() {
        ExecutionRecord record = createRecord("exec-1");
        record.markRunning();

        record.markTimeout();
        assertThat(record.state()).isEqualTo(ExecutionState.TIMEOUT);
    }

    @Test
    void validTransition_running_to_queued_viaResetForRetry() {
        ExecutionRecord record = createRecord("exec-1");
        record.markRunning();

        InvocationTask retryTask = createTask("exec-1");
        record.resetForRetry(retryTask);
        assertThat(record.state()).isEqualTo(ExecutionState.QUEUED);
        assertThat(record.startedAt()).isNull();
        assertThat(record.finishedAt()).isNull();
    }

    @Test
    void invalidTransition_queued_to_success_logsWarning() {
        // Should not throw but logs warning - transition still happens
        ExecutionRecord record = createRecord("exec-1");
        assertThat(record.state()).isEqualTo(ExecutionState.QUEUED);

        record.markSuccess("output");
        // State still changes (warn-only)
        assertThat(record.state()).isEqualTo(ExecutionState.SUCCESS);
    }

    @Test
    void invalidTransition_success_to_running_logsWarning() {
        ExecutionRecord record = createRecord("exec-1");
        record.markRunning();
        record.markSuccess("output");

        record.markRunning();
        // State still changes (warn-only), SUCCESS -> RUNNING is invalid
        assertThat(record.state()).isEqualTo(ExecutionState.RUNNING);
    }

    @Test
    void invalidTransition_error_to_success_logsWarning() {
        ExecutionRecord record = createRecord("exec-1");
        record.markRunning();
        record.markError(new ErrorInfo("ERR", "failed"));

        record.markSuccess("output");
        // State still changes (warn-only)
        assertThat(record.state()).isEqualTo(ExecutionState.SUCCESS);
    }

    @Test
    void snapshot_returnsConsistentView() {
        ExecutionRecord record = createRecord("exec-1");
        record.markRunning();

        ExecutionRecord.Snapshot snapshot = record.snapshot();
        assertThat(snapshot.executionId()).isEqualTo("exec-1");
        assertThat(snapshot.state()).isEqualTo(ExecutionState.RUNNING);
        assertThat(snapshot.startedAt()).isNotNull();
        assertThat(snapshot.finishedAt()).isNull();
    }

    private ExecutionRecord createRecord(String executionId) {
        return new ExecutionRecord(executionId, createTask(executionId));
    }

    private InvocationTask createTask(String executionId) {
        return new InvocationTask(executionId, "testFunc", null, null, null, null, null, 1);
    }
}
