use crate::execution::{ExecutionRecord, ExecutionState, ExecutionStore};
use crate::model::ConcurrencyControlMode;
use crate::queue::{InvocationTask, QueueManager};
use serde_json::Value;
use std::sync::{Arc, Mutex};

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

pub struct AsyncQueueEnqueuer {
    pub queue_manager: Arc<Mutex<QueueManager>>,
    pub execution_store: Arc<Mutex<ExecutionStore>>,
}

impl std::fmt::Debug for AsyncQueueEnqueuer {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AsyncQueueEnqueuer").finish()
    }
}

impl InvocationEnqueuer for AsyncQueueEnqueuer {
    fn enqueue(&self, _task: Option<&InvocationTask>) -> bool {
        // Use enqueue_with_capacity() instead for per-function capacity control
        false
    }

    fn enabled(&self) -> bool {
        true
    }
}

impl AsyncQueueEnqueuer {
    pub fn enqueue_with_capacity(
        &self,
        function_name: &str,
        input: Value,
        execution_id: &str,
        queue_capacity: usize,
    ) -> Result<(), String> {
        let task = InvocationTask {
            execution_id: execution_id.to_string(),
            payload: input,
            attempt: 1,
        };
        self.queue_manager
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .enqueue_with_capacity(function_name, task, queue_capacity)
            .map_err(|_| format!("Queue full for function {function_name}"))?;
        let record =
            ExecutionRecord::new(execution_id, function_name, ExecutionState::Queued);
        self.execution_store
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .put_now(record);
        Ok(())
    }
}
