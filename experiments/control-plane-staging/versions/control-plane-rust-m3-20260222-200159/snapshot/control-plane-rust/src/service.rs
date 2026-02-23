use crate::model::ConcurrencyControlMode;
use crate::queue::InvocationTask;

pub trait InvocationEnqueuer {
    fn enqueue(&self, task: Option<&InvocationTask>) -> bool;

    fn enabled(&self) -> bool;

    fn try_acquire_slot(&self, _function_name: &str) -> bool {
        true
    }

    fn release_dispatch_slot(&self, _function_name: &str) {}
}

#[derive(Debug)]
pub struct NoOpInvocationEnqueuer;

impl InvocationEnqueuer for NoOpInvocationEnqueuer {
    fn enqueue(&self, _task: Option<&InvocationTask>) -> bool {
        panic!("Async queue module not loaded")
    }

    fn enabled(&self) -> bool {
        false
    }

    fn try_acquire_slot(&self, _function_name: &str) -> bool {
        true
    }

    fn release_dispatch_slot(&self, _function_name: &str) {}
}

static NO_OP_INVOCATION_ENQUEUER: NoOpInvocationEnqueuer = NoOpInvocationEnqueuer;

pub fn no_op_invocation_enqueuer() -> &'static NoOpInvocationEnqueuer {
    &NO_OP_INVOCATION_ENQUEUER
}

pub trait ScalingMetricsSource {
    fn queue_depth(&self, function_name: &str) -> i32;

    fn in_flight(&self, function_name: &str) -> i32;

    fn set_effective_concurrency(&self, function_name: &str, value: i32);

    fn update_concurrency_controller(
        &self,
        function_name: &str,
        mode: ConcurrencyControlMode,
        target_in_flight_per_pod: i32,
    );
}

#[derive(Debug)]
pub struct NoOpScalingMetricsSource;

impl ScalingMetricsSource for NoOpScalingMetricsSource {
    fn queue_depth(&self, _function_name: &str) -> i32 {
        0
    }

    fn in_flight(&self, _function_name: &str) -> i32 {
        0
    }

    fn set_effective_concurrency(&self, _function_name: &str, _value: i32) {}

    fn update_concurrency_controller(
        &self,
        _function_name: &str,
        _mode: ConcurrencyControlMode,
        _target_in_flight_per_pod: i32,
    ) {
    }
}

static NO_OP_SCALING_METRICS_SOURCE: NoOpScalingMetricsSource = NoOpScalingMetricsSource;

pub fn no_op_scaling_metrics_source() -> &'static NoOpScalingMetricsSource {
    &NO_OP_SCALING_METRICS_SOURCE
}
