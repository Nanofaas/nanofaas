#![allow(non_snake_case)]

use control_plane_rust::execution::{ErrorInfo, ExecutionRecord, ExecutionState, ExecutionStore, InvocationTask};
use std::time::Duration;

fn make_store() -> ExecutionStore {
    ExecutionStore::new_with_durations(
        Duration::from_millis(300_000),  // ttl  (5 min)
        Duration::from_millis(120_000),  // cleanup_ttl (2 min)
        Duration::from_millis(600_000),  // stale_ttl (10 min)
    )
}

fn create_record(execution_id: &str) -> ExecutionRecord {
    let task = InvocationTask::new(execution_id, "testFunc", 1);
    ExecutionRecord::new_with_task(execution_id, task)
}

// ── original tests (still valid) ────────────────────────────────────────────

#[test]
fn evicts_finished_records_after_ttl() {
    // A completed record whose finishedAt falls within the TTL window is kept;
    // one whose finishedAt is older than TTL is evicted.
    let mut store = ExecutionStore::new_with_durations(
        Duration::from_millis(200),
        Duration::from_millis(500),
        Duration::from_millis(2000),
    );
    let mut record = ExecutionRecord::new("e1", "fn", ExecutionState::Queued);
    record.mark_running_at(0);
    record.mark_success_at(serde_json::json!(null), 0); // finishedAt = 0
    store.put_with_timestamp(record, 0);

    // now=250: anchor=0, anchor_age=250 > ttl(200) → evicted
    store.evict_expired(250);
    assert!(store.get("e1").is_none());
}

#[test]
fn keeps_running_records_until_stale_ttl() {
    let mut store = ExecutionStore::new_with_durations(
        Duration::from_millis(200),
        Duration::from_millis(500),
        Duration::from_millis(2000),
    );
    let record = ExecutionRecord::new("e2", "fn", ExecutionState::Running);
    store.put_with_timestamp(record, 0);

    // createdAt=0, stale_ttl=2000ms; at 700ms still alive
    store.evict_expired(700);
    assert!(store.get("e2").is_some());

    // at 2100ms createdAt_age=2100 > stale_ttl(2000) → evicted
    store.evict_expired(2100);
    assert!(store.get("e2").is_none());
}

// ── new parity tests ─────────────────────────────────────────────────────────

#[test]
fn eviction_doesNotRemoveRunningExecution() {
    let mut store = make_store();
    let mut record = create_record("exec-running");
    record.mark_running_at(1000);
    // Store with createdAt=0 (backdated by ~7 min equivalent)
    store.put_with_timestamp(record, 0);

    // 420_000ms ≈ 7 min; createdAt_age=420_000 < stale_ttl(600_000) → kept
    store.evict_expired(420_000);
    assert!(store.get("exec-running").is_some());
}

#[test]
fn eviction_doesNotRemoveQueuedExecution() {
    let mut store = make_store();
    let record = create_record("exec-queued");
    store.put_with_timestamp(record, 0);

    store.evict_expired(420_000);
    assert!(store.get("exec-queued").is_some());
}

#[test]
fn eviction_removesCompletedExecutionByFinishedAt() {
    // finishedAt is 420_000ms ago → anchor_age > ttl(300_000) → evicted
    let mut store = make_store();
    let mut record = create_record("exec-done");
    record.mark_running_at(1000);
    record.mark_success_at(serde_json::json!("result"), 1000); // finishedAt=1000
    store.put_with_timestamp(record, 0);

    // now=421_000 → anchor(1000), anchor_age=420_000 > ttl(300_000) → evicted
    store.evict_expired(421_000);
    assert!(store.get("exec-done").is_none());
}

#[test]
fn eviction_removesErrorExecution() {
    let mut store = make_store();
    let mut record = create_record("exec-err");
    record.mark_running_at(1000);
    record.mark_error_at(ErrorInfo::new("ERR", "failed"), 1000);
    store.put_with_timestamp(record, 0);

    store.evict_expired(421_000);
    assert!(store.get("exec-err").is_none());
}

#[test]
fn eviction_removesTimedOutExecution() {
    let mut store = make_store();
    let mut record = create_record("exec-timeout");
    record.mark_running_at(1000);
    record.mark_timeout_at(1000);
    store.put_with_timestamp(record, 0);

    store.evict_expired(421_000);
    assert!(store.get("exec-timeout").is_none());
}

#[test]
fn eviction_keepsLongRunningExecutionThatJustCompleted() {
    // createdAt is old (7 min ago), but finishedAt is recent (just now).
    // Retention anchor = finishedAt → should NOT be evicted.
    let now = 420_000u64;
    let mut store = make_store();
    let mut record = create_record("exec-long-running");
    record.mark_running_at(1000);
    // finishedAt = now (0ms ago)
    record.mark_success_at(serde_json::json!("result"), now);
    store.put_with_timestamp(record, 0); // createdAt = 0

    store.evict_expired(now);
    // anchor=now, anchor_age=0 ≤ ttl(300_000) → kept
    assert!(store.get("exec-long-running").is_some());
    assert_eq!(
        store.get("exec-long-running").unwrap().output(),
        Some(serde_json::json!("result"))
    );
}

#[test]
fn eviction_removesStaleRunningExecutionAfterStaleTtl() {
    // createdAt_age > stale_ttl → force-evict even if still running
    let mut store = make_store();
    let mut record = create_record("exec-stale-running");
    record.mark_running_at(1000);
    store.put_with_timestamp(record, 0);

    // now=660_000ms > stale_ttl(600_000) → evicted
    store.evict_expired(660_000);
    assert!(store.get("exec-stale-running").is_none());
}

#[test]
fn eviction_doesNotRemoveRecentExecution() {
    let mut store = make_store();
    let now = 1_000_000u64;
    let mut record = create_record("exec-recent");
    record.mark_running_at(now);
    record.mark_success_at(serde_json::json!("result"), now);
    store.put_with_timestamp(record, now);

    // evict at now+1: anchor_age=1ms << ttl → kept
    store.evict_expired(now + 1);
    assert!(store.get("exec-recent").is_some());
}

#[test]
fn remove_deletesExecution() {
    let mut store = make_store();
    let record = create_record("exec-to-remove");
    store.put_now(record);
    assert!(store.get("exec-to-remove").is_some());

    store.remove("exec-to-remove");
    assert!(store.get("exec-to-remove").is_none());
}
