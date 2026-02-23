#![allow(non_snake_case)]

use control_plane_rust::model::{
    ConcurrencyControlConfig, ConcurrencyControlMode, ErrorInfo, ExecutionMode, ExecutionStatus,
    FunctionSpec, InvocationRequest, InvocationResponse, InvocationResult, ResourceSpec,
    RuntimeMode, ScalingConfig, ScalingMetric, ScalingStrategy,
};
use serde_json::json;
use std::collections::HashMap;

#[test]
fn invocationResult_success_hasOutput() {
    let r = InvocationResult::success(json!("hello"));
    assert!(r.success);
    assert_eq!(r.output, Some(json!("hello")));
    assert!(r.error.is_none());
}

#[test]
fn invocationResult_error_hasErrorInfo() {
    let r = InvocationResult::error("TIMEOUT", "timed out");
    assert!(!r.success);
    assert!(r.output.is_none());
    assert!(r.error.is_some());
    let err = r.error.unwrap();
    assert_eq!(err.code, "TIMEOUT");
    assert_eq!(err.message, "timed out");
}

#[test]
fn errorInfo_recordAccessors() {
    let e = ErrorInfo {
        code: "CODE".to_string(),
        message: "msg".to_string(),
    };
    assert_eq!(e.code, "CODE");
    assert_eq!(e.message, "msg");
}

#[test]
fn invocationRequest_recordAccessors() {
    let mut metadata = HashMap::new();
    metadata.insert("k".to_string(), "v".to_string());
    let r = InvocationRequest {
        input: json!("payload"),
        metadata: Some(metadata),
    };
    assert_eq!(r.input, json!("payload"));
    assert_eq!(
        r.metadata
            .as_ref()
            .and_then(|m| m.get("k"))
            .cloned()
            .as_deref(),
        Some("v")
    );
}

#[test]
fn invocationRequest_nullMetadata() {
    let r = InvocationRequest {
        input: json!("data"),
        metadata: None,
    };
    assert!(r.metadata.is_none());
}

#[test]
fn invocationResponse_recordAccessors() {
    let err = ErrorInfo {
        code: "ERR".to_string(),
        message: "detail".to_string(),
    };
    let resp = InvocationResponse {
        execution_id: "ex-1".to_string(),
        status: "FAILED".to_string(),
        output: None,
        error: Some(err.clone()),
    };
    assert_eq!(resp.execution_id, "ex-1");
    assert_eq!(resp.status, "FAILED");
    assert!(resp.output.is_none());
    assert_eq!(resp.error, Some(err));
}

#[test]
fn executionStatus_recordAccessors() {
    let s = ExecutionStatus {
        execution_id: "ex-1".to_string(),
        status: "COMPLETED".to_string(),
        started_at_millis: Some(100),
        finished_at_millis: Some(200),
        output: Some(json!("result")),
        error: None,
        cold_start: true,
        init_duration_ms: Some(150),
    };
    assert_eq!(s.execution_id, "ex-1");
    assert_eq!(s.status, "COMPLETED");
    assert_eq!(s.started_at_millis, Some(100));
    assert_eq!(s.finished_at_millis, Some(200));
    assert_eq!(s.output, Some(json!("result")));
    assert!(s.error.is_none());
    assert!(s.cold_start);
    assert_eq!(s.init_duration_ms, Some(150));
}

#[test]
fn functionSpec_recordAccessors() {
    let spec = FunctionSpec {
        name: "echo".to_string(),
        image: Some("img:latest".to_string()),
        execution_mode: ExecutionMode::Deployment,
        runtime_mode: RuntimeMode::Http,
        concurrency: Some(4),
        queue_size: Some(100),
        max_retries: Some(3),
        scaling_config: None,
        commands: Some(vec!["cmd".to_string()]),
        env: None,
        resources: None,
        timeout_millis: Some(30_000),
        url: Some("http://svc".to_string()),
        image_pull_secrets: None,
        runtime_command: None,
    };
    assert_eq!(spec.name, "echo");
    assert_eq!(spec.image.as_deref(), Some("img:latest"));
    assert_eq!(spec.execution_mode, ExecutionMode::Deployment);
    assert_eq!(spec.runtime_mode, RuntimeMode::Http);
}

#[test]
fn executionMode_values() {
    let values = [
        ExecutionMode::Local,
        ExecutionMode::Pool,
        ExecutionMode::Deployment,
    ];
    assert_eq!(values.len(), 3);
}

#[test]
fn runtimeMode_values() {
    let values = [RuntimeMode::Http, RuntimeMode::Stdio, RuntimeMode::File];
    assert_eq!(values.len(), 3);
}

#[test]
fn scalingStrategy_values() {
    let values = [
        ScalingStrategy::Hpa,
        ScalingStrategy::Internal,
        ScalingStrategy::None,
    ];
    assert_eq!(values.len(), 3);
}

#[test]
fn concurrencyControlMode_values() {
    let values = [
        ConcurrencyControlMode::Fixed,
        ConcurrencyControlMode::StaticPerPod,
        ConcurrencyControlMode::AdaptivePerPod,
    ];
    assert_eq!(values.len(), 3);
}

#[test]
fn concurrencyControlConfig_recordAccessors() {
    let c = ConcurrencyControlConfig {
        mode: ConcurrencyControlMode::StaticPerPod,
        target_in_flight_per_pod: 2,
        min_target_in_flight_per_pod: 1,
        max_target_in_flight_per_pod: 6,
        upscale_cooldown_ms: 30_000,
        downscale_cooldown_ms: 60_000,
        high_load_threshold: 0.8,
        low_load_threshold: 0.2,
    };
    assert_eq!(c.mode, ConcurrencyControlMode::StaticPerPod);
    assert_eq!(c.target_in_flight_per_pod, 2);
    assert_eq!(c.min_target_in_flight_per_pod, 1);
    assert_eq!(c.max_target_in_flight_per_pod, 6);
    assert_eq!(c.upscale_cooldown_ms, 30_000);
    assert_eq!(c.downscale_cooldown_ms, 60_000);
    assert_eq!(c.high_load_threshold, 0.8);
    assert_eq!(c.low_load_threshold, 0.2);
}

#[test]
fn scalingMetric_recordAccessors() {
    let m = ScalingMetric {
        metric_type: "cpu".to_string(),
        target: "80".to_string(),
        name: None,
    };
    assert_eq!(m.metric_type, "cpu");
    assert_eq!(m.target, "80");
}

#[test]
fn resourceSpec_recordAccessors() {
    let r = ResourceSpec {
        cpu: "250m".to_string(),
        memory: "512Mi".to_string(),
    };
    assert_eq!(r.cpu, "250m");
    assert_eq!(r.memory, "512Mi");
}

#[test]
fn scalingConfig_recordAccessors() {
    let control = ConcurrencyControlConfig {
        mode: ConcurrencyControlMode::Fixed,
        target_in_flight_per_pod: 2,
        min_target_in_flight_per_pod: 1,
        max_target_in_flight_per_pod: 4,
        upscale_cooldown_ms: 1_000,
        downscale_cooldown_ms: 2_000,
        high_load_threshold: 0.7,
        low_load_threshold: 0.3,
    };
    let c = ScalingConfig {
        strategy: ScalingStrategy::Internal,
        min_replicas: 1,
        max_replicas: 10,
        metrics: None,
        concurrency_control: Some(control.clone()),
    };
    assert_eq!(c.strategy, ScalingStrategy::Internal);
    assert_eq!(c.min_replicas, 1);
    assert_eq!(c.max_replicas, 10);
    assert_eq!(c.concurrency_control, Some(control));
}
