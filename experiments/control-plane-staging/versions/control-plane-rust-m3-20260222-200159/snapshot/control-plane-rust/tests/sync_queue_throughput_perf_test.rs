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
async fn scheduler_ready_work_behind_blocked_function_still_makes_progress() {
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
        "blocked".to_string(),
        FunctionSpec {
            name: "blocked".to_string(),
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
            url: Some("http://127.0.0.1:9/invoke".to_string()),
            image_pull_secrets: None,
            runtime_command: None,
        },
    );
    functions.insert(
        "ready".to_string(),
        FunctionSpec {
            name: "ready".to_string(),
            image: Some("img".to_string()),
            execution_mode: ExecutionMode::Local,
            runtime_mode: RuntimeMode::Http,
            concurrency: Some(1),
            queue_size: Some(10),
            max_retries: Some(1),
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
        s.put_with_timestamp(
            ExecutionRecord::new("blocked-1", "blocked", ExecutionState::Queued),
            100,
        );
        s.put_with_timestamp(
            ExecutionRecord::new("ready-1", "ready", ExecutionState::Queued),
            101,
        );
    }
    {
        let mut q = queue.lock().unwrap();
        q.enqueue_with_capacity_and_concurrency(
            "blocked",
            InvocationTask {
                execution_id: "blocked-1".to_string(),
                payload: json!({"hello": "blocked"}),
                attempt: 1,
            },
            10,
            1,
        )
        .unwrap();
        q.enqueue_with_capacity_and_concurrency(
            "ready",
            InvocationTask {
                execution_id: "ready-1".to_string(),
                payload: json!({"hello": "ready"}),
                attempt: 1,
            },
            10,
            1,
        )
        .unwrap();
    }

    {
        let q = queue.lock().unwrap();
        assert!(q.try_acquire_slot("blocked"));
    }

    let scheduler = Scheduler::new(router);
    let handles = scheduler
        .tick_ready_functions_once(&functions, &queue, &store, &metrics)
        .await
        .unwrap();

    assert_eq!(handles.len(), 1, "ready work should still dispatch");
    for handle in handles {
        handle.await.expect("dispatch task should not panic");
    }

    let blocked = store.lock().unwrap().get("blocked-1").unwrap();
    let ready = store.lock().unwrap().get("ready-1").unwrap();
    assert_eq!(blocked.status, ExecutionState::Queued);
    assert_eq!(ready.status, ExecutionState::Success);
}
