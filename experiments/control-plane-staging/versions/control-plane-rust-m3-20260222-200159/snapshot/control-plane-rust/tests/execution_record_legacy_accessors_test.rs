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
    let mut record =
        ExecutionRecord::new_with_task("exec-2", InvocationTask::new("exec-2", "fn", 1));

    record.mark_running_at(10);
    record.mark_error_at(ErrorInfo::new("ERR", "first"), 20);
    assert!(record.last_error().is_some());
    assert!(record.output().is_none());

    record.mark_success_at(serde_json::json!("ok"), 30);
    assert_eq!(record.output(), Some(serde_json::json!("ok")));
    assert!(record.last_error().is_none());

    record.mark_error_at(ErrorInfo::new("ERR2", "second"), 40);
    assert!(record.output().is_none());
    assert_eq!(
        record.last_error().map(|e| e.code),
        Some("ERR2".to_string())
    );
}
