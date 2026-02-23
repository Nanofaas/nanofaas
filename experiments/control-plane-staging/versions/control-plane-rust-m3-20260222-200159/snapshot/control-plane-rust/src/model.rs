use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ExecutionMode {
    Deployment,
    Local,
    Pool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RuntimeMode {
    Http,
    Stdio,
    File,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ErrorInfo {
    pub code: String,
    pub message: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ScalingStrategy {
    Hpa,
    Internal,
    None,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ConcurrencyControlMode {
    Fixed,
    StaticPerPod,
    AdaptivePerPod,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ConcurrencyControlConfig {
    pub mode: ConcurrencyControlMode,
    #[serde(rename = "targetInFlightPerPod")]
    pub target_in_flight_per_pod: i32,
    #[serde(rename = "minTargetInFlightPerPod")]
    pub min_target_in_flight_per_pod: i32,
    #[serde(rename = "maxTargetInFlightPerPod")]
    pub max_target_in_flight_per_pod: i32,
    #[serde(rename = "upscaleCooldownMs")]
    pub upscale_cooldown_ms: u64,
    #[serde(rename = "downscaleCooldownMs")]
    pub downscale_cooldown_ms: u64,
    #[serde(rename = "highLoadThreshold")]
    pub high_load_threshold: f64,
    #[serde(rename = "lowLoadThreshold")]
    pub low_load_threshold: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ScalingMetric {
    #[serde(rename = "type")]
    pub metric_type: String,
    pub target: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ScalingConfig {
    pub strategy: ScalingStrategy,
    #[serde(rename = "minReplicas")]
    pub min_replicas: i32,
    #[serde(rename = "maxReplicas")]
    pub max_replicas: i32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub metrics: Option<Vec<ScalingMetric>>,
    #[serde(
        default,
        rename = "concurrencyControl",
        skip_serializing_if = "Option::is_none"
    )]
    pub concurrency_control: Option<ConcurrencyControlConfig>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ResourceSpec {
    pub cpu: String,
    pub memory: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct FunctionSpec {
    pub name: String,
    pub image: Option<String>,
    #[serde(rename = "executionMode")]
    pub execution_mode: ExecutionMode,
    #[serde(rename = "runtimeMode")]
    pub runtime_mode: RuntimeMode,
    #[serde(default)]
    pub concurrency: Option<i32>,
    #[serde(default, rename = "queueSize")]
    pub queue_size: Option<i32>,
    #[serde(default, rename = "maxRetries")]
    pub max_retries: Option<i32>,
    #[serde(default, rename = "scalingConfig")]
    pub scaling_config: Option<Value>,
    #[serde(default)]
    pub commands: Option<Vec<String>>,
    #[serde(default)]
    pub env: Option<HashMap<String, String>>,
    #[serde(default)]
    pub resources: Option<ResourceSpec>,
    #[serde(default, rename = "timeoutMillis", alias = "timeoutMs")]
    pub timeout_millis: Option<u64>,
    #[serde(default, rename = "endpointUrl", alias = "url")]
    pub url: Option<String>,
    #[serde(default, rename = "imagePullSecrets")]
    pub image_pull_secrets: Option<Vec<String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InvocationRequest {
    pub input: Value,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub metadata: Option<HashMap<String, String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InvocationResponse {
    #[serde(rename = "executionId")]
    pub execution_id: String,
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub output: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<ErrorInfo>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct InvocationResult {
    pub success: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub output: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<ErrorInfo>,
}

impl InvocationResult {
    pub fn success(output: Value) -> Self {
        Self {
            success: true,
            output: Some(output),
            error: None,
        }
    }

    pub fn error(code: &str, message: &str) -> Self {
        Self {
            success: false,
            output: None,
            error: Some(ErrorInfo {
                code: code.to_string(),
                message: message.to_string(),
            }),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ExecutionStatus {
    #[serde(rename = "executionId")]
    pub execution_id: String,
    pub status: String,
    #[serde(
        default,
        rename = "startedAtMillis",
        skip_serializing_if = "Option::is_none"
    )]
    pub started_at_millis: Option<u64>,
    #[serde(
        default,
        rename = "finishedAtMillis",
        skip_serializing_if = "Option::is_none"
    )]
    pub finished_at_millis: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub output: Option<Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<ErrorInfo>,
    #[serde(default, rename = "coldStart")]
    pub cold_start: bool,
    #[serde(
        default,
        rename = "initDurationMs",
        skip_serializing_if = "Option::is_none"
    )]
    pub init_duration_ms: Option<u64>,
}
