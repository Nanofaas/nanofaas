#![allow(non_snake_case)]

use control_plane_rust::metrics::{Metrics, FunctionTimers};
use std::time::Duration;

#[test]
fn coldStart_incrementsCounter() {
    let metrics = Metrics::new();
    metrics.cold_start("echo");
    metrics.cold_start("echo");

    assert_eq!(
        metrics.counter_value("function_cold_start_total", "echo"),
        2.0
    );
}

#[test]
fn warmStart_incrementsCounter() {
    let metrics = Metrics::new();
    metrics.warm_start("echo");

    assert_eq!(
        metrics.counter_value("function_warm_start_total", "echo"),
        1.0
    );
}

#[test]
fn initDuration_registersTimer() {
    let metrics = Metrics::new();
    let timer = metrics.init_duration("echo");
    let found = metrics.init_duration("echo");

    assert_eq!(timer, found);
}

#[test]
fn queueWait_registersTimer() {
    let metrics = Metrics::new();
    let timer = metrics.queue_wait("echo");
    let found = metrics.queue_wait("echo");

    assert_eq!(timer, found);
}

#[test]
fn e2eLatency_registersTimer() {
    let metrics = Metrics::new();
    let timer = metrics.e2e_latency("echo");
    let found = metrics.e2e_latency("echo");

    assert_eq!(timer, found);
}

#[test]
fn timers_reusesSameBundleOnWarmPath() {
    let metrics = Metrics::new();
    let first: FunctionTimers = metrics.timers("echo");
    let second: FunctionTimers = metrics.timers("echo");

    // Timer handles for the same metric key are equal (point to same entry).
    assert_eq!(first, second);
    assert_eq!(first.latency, metrics.latency("echo"));
    assert_eq!(first.init_duration, metrics.init_duration("echo"));
    assert_eq!(first.queue_wait, metrics.queue_wait("echo"));
    assert_eq!(first.e2e_latency, metrics.e2e_latency("echo"));
}

#[test]
fn dispatchRatePerSecond_usesRecentDispatchWindow() {
    let metrics = Metrics::new();

    metrics.dispatch("echo");
    metrics.dispatch("echo");
    metrics.dispatch("echo");

    let rate = metrics.dispatch_rate_per_second("echo", Duration::from_secs(1));
    assert!(rate >= 3.0);
}
