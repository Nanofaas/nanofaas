use control_plane_rust::dispatch::{DispatcherRouter, LocalDispatcher, PoolDispatcher};
use control_plane_rust::execution::{ExecutionRecord, ExecutionState, ExecutionStore};
use control_plane_rust::model::{ExecutionMode, FunctionSpec, RuntimeMode};
use control_plane_rust::queue::{InvocationTask, QueueManager};
use control_plane_rust::scheduler::Scheduler;
use serde_json::json;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

#[tokio::test]
async fn scheduler_dispatches_queued_execution_and_updates_store() {
    let queue = Arc::new(Mutex::new(QueueManager::new(10)));
    let store = Arc::new(Mutex::new(ExecutionStore::new_with_durations(
        Duration::from_secs(300),
        Duration::from_secs(120),
        Duration::from_secs(600),
    )));
    let router = DispatcherRouter::new(LocalDispatcher, PoolDispatcher::new());

    let mut functions = HashMap::new();
    functions.insert(
        "fn-a".to_string(),
        FunctionSpec {
            name: "fn-a".to_string(),
            image: Some("img".to_string()),
            execution_mode: ExecutionMode::Deployment,
            runtime_mode: RuntimeMode::Http,
            concurrency: None,
            queue_size: None,
            max_retries: None,
            scaling_config: None,
            commands: None,
            env: None,
            resources: None,
            timeout_millis: None,
            url: None,
            image_pull_secrets: None,
        },
    );

    {
        let mut s = store.lock().unwrap();
        let mut record = ExecutionRecord::new("exec-1", "fn-a", ExecutionState::Queued);
        record.output = None;
        s.put_with_timestamp(record, 100);
    }
    queue
        .lock()
        .unwrap()
        .enqueue(
            "fn-a",
            InvocationTask {
                execution_id: "exec-1".to_string(),
                payload: json!({"hello": "world"}),
                attempt: 1,
            },
        )
        .unwrap();

    let scheduler = Scheduler::new(router);
    let dispatched = scheduler
        .tick_once("fn-a", &functions, &queue, &store)
        .await
        .unwrap();
    assert!(dispatched);

    let updated = store.lock().unwrap().get("exec-1").unwrap();
    assert_eq!(updated.status, ExecutionState::Success);
    assert!(updated.output.is_some());
}
