#![allow(non_snake_case)]

use control_plane_rust::execution::{
    ErrorInfo, ExecutionRecord, ExecutionState, ExecutionStore, InvocationTask,
};
use serde_json::json;
use std::time::Duration;

fn default_store() -> ExecutionStore {
    ExecutionStore::new_with_durations(
        Duration::from_secs(5 * 60),
        Duration::from_secs(2 * 60),
        Duration::from_secs(10 * 60),
    )
}

fn create_record(execution_id: &str) -> ExecutionRecord {
    ExecutionRecord::new_with_task(
        execution_id,
        InvocationTask::new(execution_id, "testFunc", 1),
    )
}

#[test]
fn eviction_doesNotRemoveRunningExecution() {
    let mut store = default_store();
    let mut record = create_record("exec-running");
    record.mark_running_at(100);
    store.put_with_timestamp(record, 0);

    store.evict_expired(7 * 60 * 1000);

    assert!(store.get("exec-running").is_some());
}

#[test]
fn eviction_doesNotRemoveQueuedExecution() {
    let mut store = default_store();
    let record = create_record("exec-queued");
    assert_eq!(record.state(), ExecutionState::Queued);
    store.put_with_timestamp(record, 0);

    store.evict_expired(7 * 60 * 1000);

    assert!(store.get("exec-queued").is_some());
}

#[test]
fn eviction_removesCompletedExecution() {
    let mut store = default_store();
    let mut record = create_record("exec-done");
    record.mark_running_at(10);
    record.mark_success_at(json!("result"), 20);
    store.put_with_timestamp(record, 0);

    store.evict_expired(7 * 60 * 1000);

    assert!(store.get("exec-done").is_none());
}

#[test]
fn eviction_removesErrorExecution() {
    let mut store = default_store();
    let mut record = create_record("exec-err");
    record.mark_running_at(10);
    record.mark_error_at(ErrorInfo::new("ERR", "failed"), 20);
    store.put_with_timestamp(record, 0);

    store.evict_expired(7 * 60 * 1000);

    assert!(store.get("exec-err").is_none());
}

#[test]
fn eviction_removesTimedOutExecution() {
    let mut store = default_store();
    let mut record = create_record("exec-timeout");
    record.mark_running_at(10);
    record.mark_timeout_at(20);
    store.put_with_timestamp(record, 0);

    store.evict_expired(7 * 60 * 1000);

    assert!(store.get("exec-timeout").is_none());
}

#[test]
fn eviction_doesNotRemoveRecentExecution() {
    let mut store = default_store();
    let mut record = create_record("exec-recent");
    record.mark_running_at(10);
    record.mark_success_at(json!("result"), 20);
    store.put_with_timestamp(record, 0);

    store.evict_expired(60 * 1000);

    assert!(store.get("exec-recent").is_some());
}

#[test]
fn remove_deletesExecution() {
    let mut store = default_store();
    let record = create_record("exec-to-remove");
    store.put_with_timestamp(record, 0);
    assert!(store.get("exec-to-remove").is_some());

    store.remove("exec-to-remove");

    assert!(store.get("exec-to-remove").is_none());
}
