use crate::registry::{ImageValidator, NoOpImageValidator};
use crate::service::{InvocationEnqueuer, NoOpInvocationEnqueuer, NoOpScalingMetricsSource, ScalingMetricsSource};
use crate::sync::{NoOpSyncQueueGateway, SyncQueueGateway};
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
                .unwrap_or_else(|| Arc::new(NoOpInvocationEnqueuer)),
            scaling_metrics_source: scaling_metrics_source
                .unwrap_or_else(|| Arc::new(NoOpScalingMetricsSource)),
            sync_queue_gateway: sync_queue_gateway
                .unwrap_or_else(|| Arc::new(NoOpSyncQueueGateway)),
            image_validator: image_validator.unwrap_or_else(|| Arc::new(NoOpImageValidator)),
        }
    }
}
