use control_plane_rust::dispatch::{DispatcherRouter, LocalDispatcher, PoolDispatcher};
use control_plane_rust::execution::{ExecutionRecord, ExecutionState, ExecutionStore};
use control_plane_rust::model::{ExecutionMode, FunctionSpec, RuntimeMode};
use control_plane_rust::queue::{InvocationTask, QueueManager};
use control_plane_rust::scheduler::Scheduler;
use serde_json::json;
use std::collections::HashMap;
use std::time::Duration;

#[test]
fn scheduler_dispatches_queued_execution_and_updates_store() {
    let mut queue = QueueManager::new(10);
    let mut store = ExecutionStore::new_with_durations(
        Duration::from_secs(300),
        Duration::from_secs(120),
        Duration::from_secs(600),
    );
    let router = DispatcherRouter::new(Box::new(LocalDispatcher), Box::new(PoolDispatcher));

    let mut functions = HashMap::new();
    functions.insert(
        "fn-a".to_string(),
        FunctionSpec {
            name: "fn-a".to_string(),
            image: "img".to_string(),
            execution_mode: ExecutionMode::Deployment,
            runtime_mode: RuntimeMode::Http,
        },
    );

    let mut record = ExecutionRecord::new("exec-1", "fn-a", ExecutionState::Queued);
    record.output = None;
    store.put_with_timestamp(record, 100);
    queue.enqueue(
        "fn-a",
        InvocationTask {
            execution_id: "exec-1".to_string(),
            payload: json!({"hello": "world"}),
        },
    ).unwrap();

    let scheduler = Scheduler::new(router);
    let dispatched = scheduler.tick_once("fn-a", &functions, &mut queue, &mut store).unwrap();
    assert!(dispatched);

    let updated = store.get("exec-1").unwrap();
    assert_eq!(updated.status, ExecutionState::Success);
    assert!(updated.output.is_some());
}
