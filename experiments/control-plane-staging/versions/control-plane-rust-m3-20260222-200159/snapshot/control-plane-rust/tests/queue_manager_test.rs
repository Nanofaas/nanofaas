use control_plane_rust::queue::{InvocationTask, QueueManager, QueueOverflowError};
use serde_json::json;

#[test]
fn queue_manager_enqueues_and_dequeues_fifo() {
    let mut queue = QueueManager::new(2);
    queue
        .enqueue(
            "fn-a",
            InvocationTask {
                execution_id: "e1".to_string(),
                payload: json!({"n": 1}),
                attempt: 1,
            },
        )
        .unwrap();
    queue
        .enqueue(
            "fn-a",
            InvocationTask {
                execution_id: "e2".to_string(),
                payload: json!({"n": 2}),
                attempt: 1,
            },
        )
        .unwrap();

    let first = queue.take_next("fn-a").unwrap();
    let second = queue.take_next("fn-a").unwrap();
    assert_eq!(first.execution_id, "e1");
    assert_eq!(second.execution_id, "e2");
    assert!(queue.take_next("fn-a").is_none());
}

#[test]
fn queue_manager_rejects_when_function_queue_is_full() {
    let mut queue = QueueManager::new(1);
    queue
        .enqueue(
            "fn-a",
            InvocationTask {
                execution_id: "e1".to_string(),
                payload: json!({"n": 1}),
                attempt: 1,
            },
        )
        .unwrap();

    let err = queue
        .enqueue(
            "fn-a",
            InvocationTask {
                execution_id: "e2".to_string(),
                payload: json!({"n": 2}),
                attempt: 1,
            },
        )
        .unwrap_err();

    assert_eq!(
        err,
        QueueOverflowError {
            function_name: "fn-a".to_string()
        }
    );
}

#[test]
fn queue_manager_signals_work_on_enqueue_and_release_slot() {
    let mut queue = QueueManager::new(4);
    queue
        .enqueue_with_capacity_and_concurrency(
            "fn-a",
            InvocationTask {
                execution_id: "e1".to_string(),
                payload: json!({"n": 1}),
                attempt: 1,
            },
            4,
            1,
        )
        .unwrap();
    queue
        .enqueue_with_capacity_and_concurrency(
            "fn-a",
            InvocationTask {
                execution_id: "e2".to_string(),
                payload: json!({"n": 2}),
                attempt: 1,
            },
            4,
            1,
        )
        .unwrap();

    let signaled = queue.take_signaled_functions();
    assert_eq!(signaled, vec!["fn-a".to_string()]);

    assert!(queue.try_acquire_slot("fn-a"));
    let _ = queue.take_next("fn-a").unwrap();
    queue.release_slot("fn-a");

    let signaled_after_release = queue.take_signaled_functions();
    assert_eq!(signaled_after_release, vec!["fn-a".to_string()]);
}
