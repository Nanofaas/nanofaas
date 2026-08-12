package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.common.model.ErrorInfo;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;

import java.util.Map;

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

    @Test
    void markSuccess_withStatusCodeAndHeaders_reflectedInSnapshot() {
        ExecutionRecord executionRecord = createRecord("exec-1");

        executionRecord.markSuccess("body", 201, Map.of("Location", "/x"), "base64");

        ExecutionRecord.Snapshot snapshot = executionRecord.snapshot();
        assertThat(snapshot.statusCode()).isEqualTo(201);
        assertThat(snapshot.headers()).containsEntry("Location", "/x");
        assertThat(snapshot.encoding()).isEqualTo("base64");
    }

    @Test
    void markSuccess_withoutStatusCode_snapshotHasNullEnvelopeFields() {
        ExecutionRecord executionRecord = createRecord("exec-1");

        executionRecord.markSuccess("body");

        ExecutionRecord.Snapshot snapshot = executionRecord.snapshot();
        assertThat(snapshot.statusCode()).isNull();
        assertThat(snapshot.headers()).isNull();
        assertThat(snapshot.encoding()).isNull();
    }

    @Test
    void resetForRetry_clearsEnvelopeFields() {
        // Envelope fields are only ever set by markSuccess(), which transitions the
        // record into the terminal SUCCESS state; canTransition() then rejects any
        // further resetForRetry() on that record (same guard validated by
        // invalidTransition_success_to_running_isIgnored below), so a real retry
        // attempt never observes a non-null envelope in the first place. This test
        // covers the reachable path — resetForRetry() from a non-terminal (RUNNING)
        // state, where the new clearing lines still execute — as a regression guard
        // in case a future change lets envelope fields be set outside markSuccess.
        ExecutionRecord executionRecord = createRecord("exec-1");
        executionRecord.markRunning();

        executionRecord.resetForRetry(createTask("exec-1"));

        ExecutionRecord.Snapshot snapshot = executionRecord.snapshot();
        assertThat(snapshot.statusCode()).isNull();
        assertThat(snapshot.headers()).isNull();
        assertThat(snapshot.encoding()).isNull();
    }

    @Test
    void resetForRetry_afterMarkSuccess_isIgnored_envelopeUnchanged() {
        // Terminal states are final (see class javadoc): resetForRetry() after
        // markSuccess() must be a no-op, same as any other post-terminal transition
        // attempt, so a completed envelope is never silently discarded either.
        ExecutionRecord executionRecord = createRecord("exec-1");
        executionRecord.markSuccess("body", 404, Map.of("Location", "/x"), "base64");

        executionRecord.resetForRetry(createTask("exec-1"));

        assertThat(executionRecord.state()).isEqualTo(ExecutionState.SUCCESS);
        ExecutionRecord.Snapshot snapshot = executionRecord.snapshot();
        assertThat(snapshot.statusCode()).isEqualTo(404);
        assertThat(snapshot.headers()).containsEntry("Location", "/x");
        assertThat(snapshot.encoding()).isEqualTo("base64");
    }

    @Test
    void cleanup_clearsHeaders() {
        ExecutionRecord executionRecord = createRecord("exec-1");
        executionRecord.markSuccess("body", 200, Map.of("Location", "/x"), "base64");

        executionRecord.cleanup();

        ExecutionRecord.Snapshot snapshot = executionRecord.snapshot();
        assertThat(snapshot.headers()).isNull();
        assertThat(snapshot.output()).isNull();
    }

    private ExecutionRecord createRecord(String executionId) {
        return new ExecutionRecord(executionId, createTask(executionId));
    }

    private InvocationTask createTask(String executionId) {
        return new InvocationTask(executionId, "testFunc", null, null, null, null, null, 1);
    }
}
