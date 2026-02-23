#![allow(non_snake_case)]

use control_plane_rust::metrics::Metrics;

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
