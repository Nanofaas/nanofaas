#![allow(non_snake_case)]

use control_plane_rust::execution::{ErrorInfo, ExecutionRecord, ExecutionState, InvocationTask};

#[test]
fn legacyMutators_updateFieldsConsistently() {
    let original_task = InvocationTask::new("exec-1", "fn", 1);
    let mut record = ExecutionRecord::new_with_task("exec-1", original_task.clone());

    let updated_task = InvocationTask::new("exec-1", "fn", 2);

    record.update_task(updated_task.clone());
    record.set_state(ExecutionState::Running);
    record.set_started_at_millis(Some(10));
    record.set_output(Some(serde_json::json!("payload")));
    record.set_last_error(Some(ErrorInfo::new("E", "boom")));
    record.set_finished_at_millis(Some(20));

    assert_eq!(record.task(), &updated_task);
    assert_eq!(record.state(), ExecutionState::Running);
    assert_eq!(record.started_at_millis(), Some(10));
    assert_eq!(record.finished_at_millis(), Some(20));
    assert_eq!(record.output(), Some(serde_json::json!("payload")));
    assert_eq!(record.last_error(), Some(ErrorInfo::new("E", "boom")));
}

#[test]
fn markSuccess_clearsError_andMarkError_clearsOutput() {
    // Test that mark_success_at clears last_error (valid path: Queued -> Running -> Success)
    let mut record =
        ExecutionRecord::new_with_task("exec-2", InvocationTask::new("exec-2", "fn", 1));
    record.mark_running_at(10);
    record.set_last_error(Some(ErrorInfo::new("ERR", "first")));
    assert!(record.last_error().is_some());
    record.mark_success_at(serde_json::json!("ok"), 30);
    assert_eq!(record.output(), Some(serde_json::json!("ok")));
    assert!(record.last_error().is_none());

    // Test that mark_error_at clears output (valid path: Queued -> Running -> Error)
    let mut record2 =
        ExecutionRecord::new_with_task("exec-2b", InvocationTask::new("exec-2b", "fn", 1));
    record2.mark_running_at(10);
    record2.set_output(Some(serde_json::json!("previous")));
    assert!(record2.output().is_some());
    record2.mark_error_at(ErrorInfo::new("ERR2", "second"), 40);
    assert!(record2.output().is_none());
    assert_eq!(
        record2.last_error().map(|e| e.code),
        Some("ERR2".to_string())
    );
}
