#![allow(non_snake_case)]

use control_plane_rust::model::ConcurrencyControlMode;
use control_plane_rust::service::{
    no_op_invocation_enqueuer, no_op_scaling_metrics_source, InvocationEnqueuer,
    ScalingMetricsSource,
};

#[test]
fn noOpReturnsEnumSingleton() {
    let enqueuer = no_op_invocation_enqueuer();
    assert!(std::ptr::eq(enqueuer, no_op_invocation_enqueuer()));
}

#[test]
fn enabledReturnsFalse() {
    let enqueuer = no_op_invocation_enqueuer();
    assert!(!enqueuer.enabled());
}

#[test]
fn enqueueThrows() {
    let enqueuer = no_op_invocation_enqueuer();
    let result = std::panic::catch_unwind(|| {
        let _ = enqueuer.enqueue(None);
    });
    assert!(result.is_err());
}

#[test]
fn tryAcquireSlotAlwaysReturnsTrue() {
    let enqueuer = no_op_invocation_enqueuer();
    assert!(enqueuer.try_acquire_slot("functionName"));
}

#[test]
fn releaseDispatchSlotIsNoOp() {
    let enqueuer = no_op_invocation_enqueuer();
    enqueuer.release_dispatch_slot("functionName");
}

#[test]
fn returnsZeros() {
    let metrics_source = no_op_scaling_metrics_source();
    assert_eq!(metrics_source.queue_depth("functionName"), 0);
    assert_eq!(metrics_source.in_flight("functionName"), 0);
}

#[test]
fn mutatorsAreNoOp() {
    let metrics_source = no_op_scaling_metrics_source();
    metrics_source.set_effective_concurrency("functionName", 3);
    metrics_source.update_concurrency_controller(
        "functionName",
        ConcurrencyControlMode::AdaptivePerPod,
        5,
    );
}
