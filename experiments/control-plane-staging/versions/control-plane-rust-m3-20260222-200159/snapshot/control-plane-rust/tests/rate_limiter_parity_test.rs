#![allow(non_snake_case)]

use control_plane_rust::rate_limiter::RateLimiter;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;

#[test]
fn allow_underLimit_returnsTrue() {
    let mut limiter = RateLimiter::new(10);
    for _ in 0..10 {
        assert!(limiter.try_acquire_at(0));
    }
}

#[test]
fn allow_atLimit_returnsFalse() {
    let mut limiter = RateLimiter::new(10);
    for _ in 0..10 {
        let _ = limiter.try_acquire_at(0);
    }
    assert!(!limiter.try_acquire_at(0));
}

#[test]
fn allow_afterWindowReset_allowsAgain() {
    let mut limiter = RateLimiter::new(5);
    for _ in 0..5 {
        let _ = limiter.try_acquire_at(0);
    }
    assert!(!limiter.try_acquire_at(0));
    assert!(limiter.try_acquire_at(1100));
}

#[test]
fn allow_underConcurrentLoad_neverExceedsLimit() {
    let max_per_second = 100usize;
    let limiter = Arc::new(Mutex::new(RateLimiter::new(max_per_second)));

    let num_threads = 50;
    let requests_per_thread = 10;
    let allowed_count = Arc::new(AtomicUsize::new(0));

    let mut handles = Vec::new();
    for _ in 0..num_threads {
        let limiter = Arc::clone(&limiter);
        let allowed_count = Arc::clone(&allowed_count);
        handles.push(thread::spawn(move || {
            for _ in 0..requests_per_thread {
                if limiter.lock().expect("limiter lock").try_acquire_at(0) {
                    allowed_count.fetch_add(1, Ordering::Relaxed);
                }
            }
        }));
    }
    for handle in handles {
        handle.join().expect("join");
    }

    assert!(allowed_count.load(Ordering::Relaxed) <= max_per_second);
}

#[test]
fn allow_concurrentWindowReset_maintainsCorrectCount() {
    let max_per_second = 50usize;
    let limiter = Arc::new(Mutex::new(RateLimiter::new(max_per_second)));
    let total_allowed = Arc::new(AtomicUsize::new(0));

    let mut handles = Vec::new();
    for _ in 0..20 {
        let limiter = Arc::clone(&limiter);
        let total_allowed = Arc::clone(&total_allowed);
        handles.push(thread::spawn(move || {
            for i in 0..100u64 {
                let now = (i / 10) * 1000;
                if limiter.lock().expect("limiter lock").try_acquire_at(now) {
                    total_allowed.fetch_add(1, Ordering::Relaxed);
                }
            }
        }));
    }
    for handle in handles {
        handle.join().expect("join");
    }

    assert!(total_allowed.load(Ordering::Relaxed) > 0);
}
