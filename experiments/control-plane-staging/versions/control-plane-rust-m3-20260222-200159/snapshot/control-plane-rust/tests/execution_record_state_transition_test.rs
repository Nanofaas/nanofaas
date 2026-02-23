#![allow(non_snake_case)]

use control_plane_rust::execution::{ErrorInfo, ExecutionRecord, ExecutionState, InvocationTask};

#[test]
fn validTransition_queued_to_running() {
    let mut record = create_record("exec-1");
    assert_eq!(record.state(), ExecutionState::Queued);

    record.mark_running_at(10);
    assert_eq!(record.state(), ExecutionState::Running);
}

#[test]
fn validTransition_running_to_success() {
    let mut record = create_record("exec-1");
    record.mark_running_at(10);

    record.mark_success_at(serde_json::json!("output"), 20);
    assert_eq!(record.state(), ExecutionState::Success);
    assert_eq!(record.output(), Some(serde_json::json!("output")));
}

#[test]
fn validTransition_running_to_error() {
    let mut record = create_record("exec-1");
    record.mark_running_at(10);

    let error = ErrorInfo::new("TEST_ERROR", "something failed");
    record.mark_error_at(error.clone(), 20);
    assert_eq!(record.state(), ExecutionState::Error);
    assert_eq!(record.last_error(), Some(error));
}

#[test]
fn validTransition_running_to_timeout() {
    let mut record = create_record("exec-1");
    record.mark_running_at(10);

    record.mark_timeout_at(20);
    assert_eq!(record.state(), ExecutionState::Timeout);
}

#[test]
fn validTransition_running_to_queued_viaResetForRetry() {
    let mut record = create_record("exec-1");
    record.mark_running_at(10);

    let retry_task = create_task("exec-1");
    record.reset_for_retry(retry_task);
    assert_eq!(record.state(), ExecutionState::Queued);
    assert_eq!(record.started_at_millis(), None);
    assert_eq!(record.finished_at_millis(), None);
}

#[test]
fn invalidTransition_queued_to_success_logsWarning() {
    let mut record = create_record("exec-1");
    assert_eq!(record.state(), ExecutionState::Queued);

    record.mark_success_at(serde_json::json!("output"), 10);
    assert_eq!(record.state(), ExecutionState::Success);
}

#[test]
fn invalidTransition_success_to_running_logsWarning() {
    let mut record = create_record("exec-1");
    record.mark_running_at(10);
    record.mark_success_at(serde_json::json!("output"), 20);

    record.mark_running_at(30);
    assert_eq!(record.state(), ExecutionState::Running);
}

#[test]
fn invalidTransition_error_to_success_logsWarning() {
    let mut record = create_record("exec-1");
    record.mark_running_at(10);
    record.mark_error_at(ErrorInfo::new("ERR", "failed"), 20);

    record.mark_success_at(serde_json::json!("output"), 30);
    assert_eq!(record.state(), ExecutionState::Success);
}

#[test]
fn snapshot_returnsConsistentView() {
    let mut record = create_record("exec-1");
    record.mark_running_at(10);

    let snapshot = record.snapshot();
    assert_eq!(snapshot.execution_id, "exec-1");
    assert_eq!(snapshot.state, ExecutionState::Running);
    assert_eq!(snapshot.started_at_millis, Some(10));
    assert_eq!(snapshot.finished_at_millis, None);
}

#[test]
fn markColdStart_setsFieldsInSnapshot() {
    let mut record = create_record("exec-1");
    record.mark_running_at(10);
    record.mark_cold_start(350);

    let snapshot = record.snapshot();
    assert!(snapshot.cold_start);
    assert_eq!(snapshot.init_duration_ms, Some(350));
}

#[test]
fn markDispatchedAt_setsFieldInSnapshot() {
    let mut record = create_record("exec-1");
    record.mark_running_at(10);
    record.mark_dispatched_at(11);

    let snapshot = record.snapshot();
    assert_eq!(snapshot.dispatched_at_millis, Some(11));
}

#[test]
fn resetForRetry_clearsColdStartFields() {
    let mut record = create_record("exec-1");
    record.mark_running_at(10);
    record.mark_cold_start(200);
    record.mark_dispatched_at(11);

    record.reset_for_retry(create_task("exec-1"));

    let snapshot = record.snapshot();
    assert!(!snapshot.cold_start);
    assert_eq!(snapshot.init_duration_ms, None);
    assert_eq!(snapshot.dispatched_at_millis, None);
}

fn create_record(execution_id: &str) -> ExecutionRecord {
    ExecutionRecord::new_with_task(execution_id, create_task(execution_id))
}

fn create_task(execution_id: &str) -> InvocationTask {
    InvocationTask::new(execution_id, "testFunc", 1)
}
