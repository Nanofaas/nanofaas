use control_plane_rust::dispatch::{DispatcherRouter, LocalDispatcher, PoolDispatcher};
use control_plane_rust::execution::{ExecutionRecord, ExecutionState, ExecutionStore};
use control_plane_rust::metrics::Metrics;
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
            runtime_command: None,
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
    let handle = scheduler
        .tick_once("fn-a", &functions, &queue, &store, &Metrics::new())
        .await
        .unwrap();
    assert!(handle.is_some());
    if let Some(h) = handle {
        let _ = h.await;
    }

    let updated = store.lock().unwrap().get("exec-1").unwrap();
    assert_eq!(updated.status, ExecutionState::Success);
    assert!(updated.output.is_some());
}

#[tokio::test]
async fn scheduler_releases_dispatch_slot_after_error_and_continues() {
    let queue = Arc::new(Mutex::new(QueueManager::new(10)));
    let store = Arc::new(Mutex::new(ExecutionStore::new_with_durations(
        Duration::from_secs(300),
        Duration::from_secs(120),
        Duration::from_secs(600),
    )));
    let router = DispatcherRouter::new(LocalDispatcher, PoolDispatcher::new());
    let metrics = Metrics::new();

    let mut functions = HashMap::new();
    functions.insert(
        "fn-a".to_string(),
        FunctionSpec {
            name: "fn-a".to_string(),
            image: Some("img".to_string()),
            execution_mode: ExecutionMode::Deployment,
            runtime_mode: RuntimeMode::Http,
            concurrency: Some(1),
            queue_size: Some(10),
            max_retries: Some(1),
            scaling_config: None,
            commands: None,
            env: None,
            resources: None,
            timeout_millis: None,
            url: Some("http://127.0.0.1:9/invoke".to_string()), // unreachable => dispatch error
            image_pull_secrets: None,
            runtime_command: None,
        },
    );

    {
        let mut s = store.lock().unwrap();
        s.put_with_timestamp(
            ExecutionRecord::new("exec-err-1", "fn-a", ExecutionState::Queued),
            100,
        );
        s.put_with_timestamp(
            ExecutionRecord::new("exec-err-2", "fn-a", ExecutionState::Queued),
            101,
        );
    }
    {
        let mut q = queue.lock().unwrap();
        q.enqueue_with_capacity_and_concurrency(
            "fn-a",
            InvocationTask {
                execution_id: "exec-err-1".to_string(),
                payload: json!({"hello": "first"}),
                attempt: 1,
            },
            10,
            1,
        )
        .unwrap();
        q.enqueue_with_capacity_and_concurrency(
            "fn-a",
            InvocationTask {
                execution_id: "exec-err-2".to_string(),
                payload: json!({"hello": "second"}),
                attempt: 1,
            },
            10,
            1,
        )
        .unwrap();
    }

    let scheduler = Scheduler::new(router);
    let h1 = scheduler
        .tick_once("fn-a", &functions, &queue, &store, &metrics)
        .await
        .unwrap();
    assert!(h1.is_some());
    if let Some(h) = h1 {
        let _ = h.await;
    }
    assert_eq!(queue.lock().unwrap().in_flight("fn-a"), 0);
    let h2 = scheduler
        .tick_once("fn-a", &functions, &queue, &store, &metrics)
        .await
        .unwrap();
    assert!(h2.is_some());
    if let Some(h) = h2 {
        let _ = h.await;
    }
    assert_eq!(queue.lock().unwrap().in_flight("fn-a"), 0);

    let first = store.lock().unwrap().get("exec-err-1").unwrap();
    let second = store.lock().unwrap().get("exec-err-2").unwrap();
    assert_eq!(first.status, ExecutionState::Error);
    assert_eq!(second.status, ExecutionState::Error);
}
