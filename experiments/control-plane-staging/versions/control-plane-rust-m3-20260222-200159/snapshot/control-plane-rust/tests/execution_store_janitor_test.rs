use control_plane_rust::execution::{
    spawn_execution_store_janitor, ExecutionRecord, ExecutionState, ExecutionStore,
};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[tokio::test]
async fn janitor_evicts_expired_completed_records() {
    let store = Arc::new(Mutex::new(ExecutionStore::new_with_durations(
        Duration::from_millis(20),
        Duration::from_millis(10),
        Duration::from_millis(60),
    )));

    {
        let mut guard = store.lock().unwrap_or_else(|e| e.into_inner());
        guard.put_with_timestamp(
            ExecutionRecord::new("janitor-e1", "echo", ExecutionState::Success),
            now_millis(),
        );
    }

    spawn_execution_store_janitor(Arc::clone(&store), Duration::from_millis(5));
    tokio::time::sleep(Duration::from_millis(80)).await;

    let guard = store.lock().unwrap_or_else(|e| e.into_inner());
    assert!(guard.get("janitor-e1").is_none());
}
