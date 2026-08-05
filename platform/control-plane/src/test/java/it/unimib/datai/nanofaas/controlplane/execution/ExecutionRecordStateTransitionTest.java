package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.common.model.ErrorInfo;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionRecordStateTransitionTest {

    @Test
    void validTransition_queued_to_running() {
        ExecutionRecord executionRecord = createRecord("exec-1");
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.QUEUED);

        executionRecord.markRunning();
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.RUNNING);
    }

    @Test
    void validTransition_running_to_success() {
        ExecutionRecord executionRecord = createRecord("exec-1");
        executionRecord.markRunning();

        executionRecord.markSuccess("output");
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(executionRecord.output()).isEqualTo("output");
    }

    @Test
    void validTransition_running_to_error() {
        ExecutionRecord executionRecord = createRecord("exec-1");
        executionRecord.markRunning();

        ErrorInfo error = new ErrorInfo("TEST_ERROR", "something failed");
        executionRecord.markError(error);
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.ERROR);
        assertThat(executionRecord.lastError()).isEqualTo(error);
    }

    @Test
    void validTransition_running_to_timeout() {
        ExecutionRecord executionRecord = createRecord("exec-1");
        executionRecord.markRunning();

        executionRecord.markTimeout();
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.TIMEOUT);
    }

    @Test
    void validTransition_running_to_queued_viaResetForRetry() {
        ExecutionRecord executionRecord = createRecord("exec-1");
        executionRecord.markRunning();

        InvocationTask retryTask = createTask("exec-1");
        executionRecord.resetForRetry(retryTask);
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.QUEUED);
        assertThat(executionRecord.startedAt()).isNull();
        assertThat(executionRecord.finishedAt()).isNull();
    }

    @Test
    void validTransition_queued_to_success() {
        ExecutionRecord executionRecord = createRecord("exec-1");
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.QUEUED);

        executionRecord.markSuccess("output");
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(executionRecord.output()).isEqualTo("output");
    }

    @Test
    void invalidTransition_success_to_running_isIgnored() {
        ExecutionRecord executionRecord = createRecord("exec-1");
        executionRecord.markRunning();
        executionRecord.markSuccess("output");

        executionRecord.markRunning();
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(executionRecord.output()).isEqualTo("output");
    }

    @Test
    void invalidTransition_error_to_success_isIgnored() {
        ExecutionRecord executionRecord = createRecord("exec-1");
        executionRecord.markRunning();
        executionRecord.markError(new ErrorInfo("ERR", "failed"));

        executionRecord.markSuccess("output");
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.ERROR);
        assertThat(executionRecord.output()).isNull();
    }

    @Test
    void invalidTransition_timeout_to_success_isIgnored() {
        ExecutionRecord executionRecord = createRecord("exec-1");
        executionRecord.markRunning();
        executionRecord.markTimeout();

        executionRecord.markSuccess("late-output");

        assertThat(executionRecord.state()).isEqualTo(ExecutionState.TIMEOUT);
        assertThat(executionRecord.output()).isNull();
    }

    @Test
    void snapshot_returnsConsistentView() {
        ExecutionRecord executionRecord = createRecord("exec-1");
        executionRecord.markRunning();

        ExecutionRecord.Snapshot snapshot = executionRecord.snapshot();
        assertThat(snapshot.executionId()).isEqualTo("exec-1");
        assertThat(snapshot.state()).isEqualTo(ExecutionState.RUNNING);
        assertThat(snapshot.startedAt()).isNotNull();
        assertThat(snapshot.finishedAt()).isNull();
    }

    @Test
    void markColdStart_setsFieldsInSnapshot() {
        ExecutionRecord executionRecord = createRecord("exec-1");
        executionRecord.markRunning();
        executionRecord.markColdStart(350);

        ExecutionRecord.Snapshot snapshot = executionRecord.snapshot();
        assertThat(snapshot.coldStart()).isTrue();
        assertThat(snapshot.initDurationMs()).isEqualTo(350L);
    }

    @Test
    void markDispatchedAt_setsFieldInSnapshot() {
        ExecutionRecord executionRecord = createRecord("exec-1");
        executionRecord.markRunning();
        executionRecord.markDispatchedAt();

        ExecutionRecord.Snapshot snapshot = executionRecord.snapshot();
        assertThat(snapshot.dispatchedAt()).isNotNull();
    }

    @Test
    void resetForRetry_clearsColdStartFields() {
        ExecutionRecord executionRecord = createRecord("exec-1");
        executionRecord.markRunning();
        executionRecord.markColdStart(200);
        executionRecord.markDispatchedAt();

        executionRecord.resetForRetry(createTask("exec-1"));

        ExecutionRecord.Snapshot snapshot = executionRecord.snapshot();
        assertThat(snapshot.coldStart()).isFalse();
        assertThat(snapshot.initDurationMs()).isNull();
        assertThat(snapshot.dispatchedAt()).isNull();
    }

    private ExecutionRecord createRecord(String executionId) {
        return new ExecutionRecord(executionId, createTask(executionId));
    }

    private InvocationTask createTask(String executionId) {
        return new InvocationTask(executionId, "testFunc", null, null, null, null, null, 1);
    }
}
