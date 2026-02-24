use control_plane_rust::execution::{ExecutionRecord, ExecutionState, ExecutionStore};
use std::time::Duration;

#[test]
fn evicts_finished_records_after_ttl() {
    let mut store = ExecutionStore::new_with_durations(
        Duration::from_millis(200),
        Duration::from_millis(500),
        Duration::from_millis(2000),
    );
    let record = ExecutionRecord::new("e1", "fn", ExecutionState::Success);
    store.put_with_timestamp(record, 0);

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

    store.evict_expired(700);
    assert!(store.get("e2").is_some());

    store.evict_expired(2100);
    assert!(store.get("e2").is_none());
}
