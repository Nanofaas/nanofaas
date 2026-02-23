use crate::model::ConcurrencyControlMode;
use crate::registry::{no_op_image_validator, ImageValidator};
use crate::service::{
    no_op_invocation_enqueuer, no_op_scaling_metrics_source, InvocationEnqueuer,
    ScalingMetricsSource,
};
use crate::sync::{no_op_sync_queue_gateway, SyncQueueGateway};
use std::sync::Arc;

pub struct CoreDefaults {
    pub invocation_enqueuer: Arc<dyn InvocationEnqueuer + Send + Sync>,
    pub scaling_metrics_source: Arc<dyn ScalingMetricsSource + Send + Sync>,
    pub sync_queue_gateway: Arc<dyn SyncQueueGateway + Send + Sync>,
    pub image_validator: Arc<dyn ImageValidator + Send + Sync>,
}

impl CoreDefaults {
    pub fn new(
        invocation_enqueuer: Option<Arc<dyn InvocationEnqueuer + Send + Sync>>,
        scaling_metrics_source: Option<Arc<dyn ScalingMetricsSource + Send + Sync>>,
        sync_queue_gateway: Option<Arc<dyn SyncQueueGateway + Send + Sync>>,
        image_validator: Option<Arc<dyn ImageValidator + Send + Sync>>,
    ) -> Self {
        Self {
            invocation_enqueuer: invocation_enqueuer
                .unwrap_or_else(|| Arc::new(NoOpInvocationEnqueuerAdapter)),
            scaling_metrics_source: scaling_metrics_source
                .unwrap_or_else(|| Arc::new(NoOpScalingMetricsSourceAdapter)),
            sync_queue_gateway: sync_queue_gateway
                .unwrap_or_else(|| Arc::new(NoOpSyncQueueGatewayAdapter)),
            image_validator: image_validator.unwrap_or_else(|| Arc::new(NoOpImageValidatorAdapter)),
        }
    }
}

struct NoOpInvocationEnqueuerAdapter;

impl InvocationEnqueuer for NoOpInvocationEnqueuerAdapter {
    fn enqueue(&self, task: Option<&crate::queue::InvocationTask>) -> bool {
        no_op_invocation_enqueuer().enqueue(task)
    }

    fn enabled(&self) -> bool {
        no_op_invocation_enqueuer().enabled()
    }

    fn try_acquire_slot(&self, function_name: &str) -> bool {
        no_op_invocation_enqueuer().try_acquire_slot(function_name)
    }

    fn release_dispatch_slot(&self, function_name: &str) {
        no_op_invocation_enqueuer().release_dispatch_slot(function_name)
    }
}

struct NoOpScalingMetricsSourceAdapter;

impl ScalingMetricsSource for NoOpScalingMetricsSourceAdapter {
    fn queue_depth(&self, function_name: &str) -> i32 {
        no_op_scaling_metrics_source().queue_depth(function_name)
    }

    fn in_flight(&self, function_name: &str) -> i32 {
        no_op_scaling_metrics_source().in_flight(function_name)
    }

    fn set_effective_concurrency(&self, function_name: &str, value: i32) {
        no_op_scaling_metrics_source().set_effective_concurrency(function_name, value)
    }

    fn update_concurrency_controller(
        &self,
        function_name: &str,
        mode: ConcurrencyControlMode,
        target_in_flight_per_pod: i32,
    ) {
        no_op_scaling_metrics_source().update_concurrency_controller(
            function_name,
            mode,
            target_in_flight_per_pod,
        )
    }
}

struct NoOpSyncQueueGatewayAdapter;

impl SyncQueueGateway for NoOpSyncQueueGatewayAdapter {
    fn enqueue_or_throw(&self, task: Option<&crate::queue::InvocationTask>) {
        no_op_sync_queue_gateway().enqueue_or_throw(task)
    }

    fn enabled(&self) -> bool {
        no_op_sync_queue_gateway().enabled()
    }

    fn retry_after_seconds(&self) -> i32 {
        no_op_sync_queue_gateway().retry_after_seconds()
    }
}

struct NoOpImageValidatorAdapter;

impl ImageValidator for NoOpImageValidatorAdapter {
    fn validate(&self, spec: Option<&crate::model::FunctionSpec>) {
        no_op_image_validator().validate(spec)
    }
}
