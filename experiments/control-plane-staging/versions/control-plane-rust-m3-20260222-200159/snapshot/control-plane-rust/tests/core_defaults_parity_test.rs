#![allow(non_snake_case)]

use control_plane_rust::core_defaults::CoreDefaults;
use control_plane_rust::model::{ConcurrencyControlMode, FunctionSpec};
use control_plane_rust::registry::ImageValidator;
use control_plane_rust::service::{InvocationEnqueuer, ScalingMetricsSource};
use control_plane_rust::sync::SyncQueueGateway;
use std::sync::Arc;

#[test]
fn registersNoOpBeansWhenMissing() {
    let defaults = CoreDefaults::new(None, None, None, None);

    assert!(!defaults.invocation_enqueuer.enabled());
    assert_eq!(
        defaults.scaling_metrics_source.queue_depth("functionName"),
        0
    );
    assert_eq!(defaults.scaling_metrics_source.in_flight("functionName"), 0);
    assert!(!defaults.sync_queue_gateway.enabled());
    defaults.image_validator.validate(None);
}

#[test]
fn doesNotOverrideExplicitBeans() {
    struct CustomInvocationEnqueuer;
    impl InvocationEnqueuer for CustomInvocationEnqueuer {
        fn enqueue(&self, _task: Option<&control_plane_rust::queue::InvocationTask>) -> bool {
            true
        }

        fn enabled(&self) -> bool {
            true
        }
    }

    struct CustomScalingMetricsSource;
    impl ScalingMetricsSource for CustomScalingMetricsSource {
        fn queue_depth(&self, _function_name: &str) -> i32 {
            7
        }

        fn in_flight(&self, _function_name: &str) -> i32 {
            3
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

    struct CustomSyncQueueGateway;
    impl SyncQueueGateway for CustomSyncQueueGateway {
        fn enqueue_or_throw(&self, _task: Option<&control_plane_rust::queue::InvocationTask>) {}

        fn enabled(&self) -> bool {
            true
        }

        fn retry_after_seconds(&self) -> i32 {
            9
        }
    }

    struct CustomImageValidator;
    impl ImageValidator for CustomImageValidator {
        fn validate(&self, _spec: Option<&FunctionSpec>) {}
    }

    let custom_invocation: Arc<dyn InvocationEnqueuer + Send + Sync> =
        Arc::new(CustomInvocationEnqueuer);
    let custom_scaling: Arc<dyn ScalingMetricsSource + Send + Sync> =
        Arc::new(CustomScalingMetricsSource);
    let custom_sync: Arc<dyn SyncQueueGateway + Send + Sync> = Arc::new(CustomSyncQueueGateway);
    let custom_image: Arc<dyn ImageValidator + Send + Sync> = Arc::new(CustomImageValidator);

    let defaults = CoreDefaults::new(
        Some(Arc::clone(&custom_invocation)),
        Some(Arc::clone(&custom_scaling)),
        Some(Arc::clone(&custom_sync)),
        Some(Arc::clone(&custom_image)),
    );

    assert!(Arc::ptr_eq(
        &defaults.invocation_enqueuer,
        &custom_invocation
    ));
    assert!(Arc::ptr_eq(
        &defaults.scaling_metrics_source,
        &custom_scaling
    ));
    assert!(Arc::ptr_eq(&defaults.sync_queue_gateway, &custom_sync));
    assert!(Arc::ptr_eq(&defaults.image_validator, &custom_image));
}
