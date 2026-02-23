use crate::model::{ExecutionMode, FunctionSpec, InvocationResult, ScalingConfig, ScalingMetric};
use reqwest::Client;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::time::Duration;

#[derive(Debug, Clone)]
pub struct DispatchResult {
    pub status: String,
    pub output: Option<Value>,
    pub dispatcher: String,
    pub cold_start: bool,
    pub init_duration_ms: Option<u64>,
}

#[derive(Debug, Default)]
pub struct LocalDispatcher;

impl LocalDispatcher {
    pub async fn dispatch(
        &self,
        _function: &FunctionSpec,
        payload: &Value,
        _execution_id: &str,
    ) -> DispatchResult {
        DispatchResult {
            status: "SUCCESS".to_string(),
            output: Some(payload.clone()),
            dispatcher: "local".to_string(),
            cold_start: false,
            init_duration_ms: None,
        }
    }
}

#[derive(Debug, Clone)]
pub struct PoolDispatcher {
    client: Client,
}

impl PoolDispatcher {
    pub fn new() -> Self {
        Self {
            client: Client::new(),
        }
    }

    pub async fn dispatch(
        &self,
        function: &FunctionSpec,
        payload: &Value,
        execution_id: &str,
    ) -> DispatchResult {
        let endpoint = match function
            .url
            .as_deref()
            .map(str::trim)
            .filter(|e| !e.is_empty())
        {
            Some(ep) => ep.to_string(),
            None => {
                return DispatchResult {
                    status: "SUCCESS".to_string(),
                    output: Some(payload.clone()),
                    dispatcher: "pool".to_string(),
                    cold_start: false,
                    init_duration_ms: None,
                };
            }
        };

        let timeout_ms = function.timeout_millis.unwrap_or(30_000);
        let runtime_request = json!({ "input": payload });

        let response = match self
            .client
            .post(&endpoint)
            .timeout(Duration::from_millis(timeout_ms))
            .header("X-Execution-Id", execution_id)
            .json(&runtime_request)
            .send()
            .await
        {
            Ok(r) => r,
            Err(_) => {
                return DispatchResult {
                    status: "ERROR".to_string(),
                    output: None,
                    dispatcher: "pool".to_string(),
                    cold_start: false,
                    init_duration_ms: None,
                };
            }
        };

        if response.status().as_u16() >= 400 {
            return DispatchResult {
                status: "ERROR".to_string(),
                output: None,
                dispatcher: "pool".to_string(),
                cold_start: false,
                init_duration_ms: None,
            };
        }

        // Read cold-start headers before consuming the response body.
        let cold_start = response
            .headers()
            .get("X-Cold-Start")
            .and_then(|v| v.to_str().ok())
            .map(|v| v.eq_ignore_ascii_case("true"))
            .unwrap_or(false);
        let init_duration_ms = response
            .headers()
            .get("X-Init-Duration-Ms")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.parse::<u64>().ok());

        let content_type = response
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("application/json")
            .to_string();

        let body = match response.text().await {
            Ok(b) => b,
            Err(_) => {
                return DispatchResult {
                    status: "ERROR".to_string(),
                    output: None,
                    dispatcher: "pool".to_string(),
                    cold_start: false,
                    init_duration_ms: None,
                };
            }
        };

        let output = if content_type.starts_with("text/plain") {
            Value::String(body)
        } else {
            serde_json::from_str::<Value>(&body).unwrap_or(Value::String(body))
        };

        DispatchResult {
            status: "SUCCESS".to_string(),
            output: Some(output),
            dispatcher: "pool".to_string(),
            cold_start,
            init_duration_ms,
        }
    }
}

pub struct DispatcherRouter {
    local: LocalDispatcher,
    pool: PoolDispatcher,
}

impl DispatcherRouter {
    pub fn new(local: LocalDispatcher, pool: PoolDispatcher) -> Self {
        Self { local, pool }
    }

    pub async fn dispatch(
        &self,
        function: &FunctionSpec,
        payload: &Value,
        execution_id: &str,
    ) -> DispatchResult {
        match function.execution_mode {
            ExecutionMode::Local => self.local.dispatch(function, payload, execution_id).await,
            ExecutionMode::Deployment | ExecutionMode::Pool => {
                self.pool.dispatch(function, payload, execution_id).await
            }
        }
    }
}

impl Clone for DispatcherRouter {
    fn clone(&self) -> Self {
        Self {
            local: LocalDispatcher,
            pool: self.pool.clone(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MetricTarget {
    pub target_type: String,
    pub average_utilization: Option<i32>,
    pub value: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResourceMetricSpec {
    pub name: String,
    pub target: MetricTarget,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExternalMetricIdentifier {
    pub name: String,
    pub selector: HashMap<String, String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExternalMetricSpec {
    pub metric: ExternalMetricIdentifier,
    pub target: MetricTarget,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MetricSpec {
    pub kind: String,
    pub resource: Option<ResourceMetricSpec>,
    pub external: Option<ExternalMetricSpec>,
}

#[derive(Debug, Clone, Copy, Default)]
pub struct KubernetesMetricsTranslator;

impl KubernetesMetricsTranslator {
    pub fn to_metric_specs(&self, config: &ScalingConfig, spec: &FunctionSpec) -> Vec<MetricSpec> {
        let Some(metrics) = &config.metrics else {
            return vec![];
        };

        metrics
            .iter()
            .filter_map(|metric| self.to_k8s_metric_spec(metric, &spec.name))
            .collect()
    }

    fn to_k8s_metric_spec(
        &self,
        metric: &ScalingMetric,
        function_name: &str,
    ) -> Option<MetricSpec> {
        let target_value = parse_target(&metric.target);
        match metric.metric_type.as_str() {
            "cpu" | "memory" => Some(MetricSpec {
                kind: "Resource".to_string(),
                resource: Some(ResourceMetricSpec {
                    name: metric.metric_type.clone(),
                    target: MetricTarget {
                        target_type: "Utilization".to_string(),
                        average_utilization: Some(target_value),
                        value: None,
                    },
                }),
                external: None,
            }),
            "queue_depth" | "in_flight" | "rps" => {
                let mut selector = HashMap::new();
                selector.insert("function".to_string(), function_name.to_string());
                Some(MetricSpec {
                    kind: "External".to_string(),
                    resource: None,
                    external: Some(ExternalMetricSpec {
                        metric: ExternalMetricIdentifier {
                            name: format!("nanofaas_{}", metric.metric_type),
                            selector,
                        },
                        target: MetricTarget {
                            target_type: "Value".to_string(),
                            average_utilization: None,
                            value: Some(target_value.to_string()),
                        },
                    }),
                })
            }
            "prometheus" => {
                let mut selector = HashMap::new();
                selector.insert("function".to_string(), function_name.to_string());
                Some(MetricSpec {
                    kind: "External".to_string(),
                    resource: None,
                    external: Some(ExternalMetricSpec {
                        metric: ExternalMetricIdentifier {
                            name: format!("nanofaas_custom_{function_name}"),
                            selector,
                        },
                        target: MetricTarget {
                            target_type: "Value".to_string(),
                            average_utilization: None,
                            value: Some(target_value.to_string()),
                        },
                    }),
                })
            }
            _ => None,
        }
    }
}

fn parse_target(target: &str) -> i32 {
    target.parse::<i32>().unwrap_or(50)
}

#[derive(Debug, Clone)]
pub struct PoolInvocationTask {
    pub execution_id: String,
    pub function_name: String,
    pub function_spec: FunctionSpec,
    pub payload: Value,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PoolHttpResponse {
    pub status_code: u16,
    pub body: String,
    pub content_type: String,
    pub headers: HashMap<String, String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PoolHttpError {
    Timeout,
    Transport(String),
}

pub trait PoolHttpClient: Send + Sync {
    fn invoke(
        &self,
        endpoint: &str,
        payload: &Value,
        timeout_ms: u64,
    ) -> Result<PoolHttpResponse, PoolHttpError>;
}

#[derive(Debug, Clone, PartialEq)]
pub struct PoolDispatchOutcome {
    pub result: InvocationResult,
    pub cold_start: bool,
    pub init_duration_ms: Option<u64>,
}

pub struct PoolInvocationDispatcher<C: PoolHttpClient> {
    client: C,
}

impl<C: PoolHttpClient> PoolInvocationDispatcher<C> {
    pub fn new(client: C) -> Self {
        Self { client }
    }

    pub fn dispatch(&self, task: &PoolInvocationTask) -> PoolDispatchOutcome {
        let Some(endpoint) = task.function_spec.url.as_deref() else {
            return PoolDispatchOutcome {
                result: InvocationResult::error(
                    "POOL_ENDPOINT_MISSING",
                    "POOL endpoint URL is not configured",
                ),
                cold_start: false,
                init_duration_ms: None,
            };
        };

        let timeout_ms = task.function_spec.timeout_millis.unwrap_or(30_000);
        let response = self.client.invoke(endpoint, &task.payload, timeout_ms);
        match response {
            Err(PoolHttpError::Timeout) => PoolDispatchOutcome {
                result: InvocationResult::error(
                    "POOL_TIMEOUT",
                    &format!("POOL dispatch timed out after {timeout_ms}ms"),
                ),
                cold_start: false,
                init_duration_ms: None,
            },
            Err(PoolHttpError::Transport(message)) => PoolDispatchOutcome {
                result: InvocationResult::error(
                    "POOL_ERROR",
                    &format!("POOL dispatch failed: {message}"),
                ),
                cold_start: false,
                init_duration_ms: None,
            },
            Ok(response) => {
                if response.status_code >= 400 {
                    return PoolDispatchOutcome {
                        result: InvocationResult::error(
                            "POOL_ERROR",
                            &format!("POOL endpoint returned status {}", response.status_code),
                        ),
                        cold_start: false,
                        init_duration_ms: None,
                    };
                }

                let output = if response.content_type.starts_with("text/plain") {
                    Value::String(response.body.clone())
                } else {
                    serde_json::from_str::<Value>(&response.body)
                        .unwrap_or_else(|_| Value::String(response.body.clone()))
                };

                let cold_start = response
                    .headers
                    .get("X-Cold-Start")
                    .map(|value| value.eq_ignore_ascii_case("true"))
                    .unwrap_or(false);
                let init_duration_ms = response
                    .headers
                    .get("X-Init-Duration-Ms")
                    .and_then(|value| value.parse::<u64>().ok());

                PoolDispatchOutcome {
                    result: InvocationResult::success(output),
                    cold_start,
                    init_duration_ms,
                }
            }
        }
    }
}
