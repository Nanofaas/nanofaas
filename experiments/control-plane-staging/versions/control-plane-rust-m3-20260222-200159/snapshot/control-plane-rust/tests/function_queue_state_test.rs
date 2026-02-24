use control_plane_rust::queue::{FunctionQueueState, InvocationTask};
use serde_json::json;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

fn task(execution_id: &str) -> InvocationTask {
    InvocationTask {
        execution_id: execution_id.to_string(),
        payload: json!({"id": execution_id}),
        attempt: 1,
    }
}

#[test]
fn try_acquire_slot_under_limit_returns_true() {
    let state = FunctionQueueState::new("fn".to_string(), 10, 2);
    assert!(state.try_acquire_slot());
    assert!(state.try_acquire_slot());
}

#[test]
fn try_acquire_slot_at_limit_returns_false() {
    let state = FunctionQueueState::new("fn".to_string(), 10, 1);
    assert!(state.try_acquire_slot());
    assert!(!state.try_acquire_slot());
}

#[test]
fn release_slot_allows_new_acquire() {
    let state = FunctionQueueState::new("fn".to_string(), 10, 1);
    assert!(state.try_acquire_slot());
    assert!(!state.try_acquire_slot());
    state.release_slot();
    assert!(state.try_acquire_slot());
}

#[test]
fn offer_and_poll_respect_queue_capacity() {
    let state = FunctionQueueState::new("fn".to_string(), 2, 1);
    assert!(state.offer(task("e1")));
    assert!(state.offer(task("e2")));
    assert!(!state.offer(task("e3")));
    assert_eq!(state.poll().unwrap().execution_id, "e1");
    assert_eq!(state.poll().unwrap().execution_id, "e2");
    assert!(state.poll().is_none());
}

#[test]
fn configured_concurrency_reduces_effective_when_lowered() {
    let state = FunctionQueueState::new("fn".to_string(), 10, 6);
    state.set_effective_concurrency(5);
    state.concurrency(3);
    assert_eq!(state.configured_concurrency(), 3);
    assert_eq!(state.effective_concurrency(), 3);
}

#[test]
fn try_acquire_slot_under_concurrent_load_never_exceeds_limit() {
    let state = Arc::new(FunctionQueueState::new("fn".to_string(), 50, 4));
    let peak = Arc::new(AtomicUsize::new(0));
    let mut handles = Vec::new();
    for _ in 0..16 {
        let state = Arc::clone(&state);
        let peak = Arc::clone(&peak);
        handles.push(std::thread::spawn(move || {
            for _ in 0..200 {
                if state.try_acquire_slot() {
                    let current = state.in_flight();
                    let mut old = peak.load(Ordering::SeqCst);
                    while current > old
                        && peak
                            .compare_exchange(old, current, Ordering::SeqCst, Ordering::SeqCst)
                            .is_err()
                    {
                        old = peak.load(Ordering::SeqCst);
                    }
                    std::thread::yield_now();
                    state.release_slot();
                }
            }
        }));
    }

    for handle in handles {
        handle.join().expect("thread join");
    }

    assert!(peak.load(Ordering::SeqCst) <= 4);
}
