use crate::model::{ExecutionMode, FunctionSpec, InvocationResult, ScalingConfig, ScalingMetric};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::sync::Arc;
use std::time::Duration;

#[derive(Debug, Clone)]
pub struct DispatchResult {
    pub status: String,
    pub output: Option<Value>,
    pub dispatcher: String,
}

pub trait Dispatcher: Send + Sync {
    fn dispatch(
        &self,
        function: &FunctionSpec,
        payload: &Value,
        execution_id: &str,
    ) -> DispatchResult;
}

#[derive(Debug, Default)]
pub struct LocalDispatcher;

impl Dispatcher for LocalDispatcher {
    fn dispatch(
        &self,
        _function: &FunctionSpec,
        payload: &Value,
        _execution_id: &str,
    ) -> DispatchResult {
        DispatchResult {
            status: "SUCCESS".to_string(),
            output: Some(payload.clone()),
            dispatcher: "local".to_string(),
        }
    }
}

#[derive(Debug, Default)]
pub struct PoolDispatcher;

impl Dispatcher for PoolDispatcher {
    fn dispatch(
        &self,
        function: &FunctionSpec,
        payload: &Value,
        execution_id: &str,
    ) -> DispatchResult {
        let endpoint = function
            .url
            .as_deref()
            .map(str::trim)
            .filter(|endpoint| !endpoint.is_empty());

        let Some(endpoint) = endpoint else {
            return DispatchResult {
                status: "SUCCESS".to_string(),
                output: Some(payload.clone()),
                dispatcher: "pool".to_string(),
            };
        };

        let timeout_ms = function.timeout_millis.unwrap_or(30_000);
        let runtime_request = json!({ "input": payload });
        let http_response =
            match invoke_pool_http(endpoint, &runtime_request, timeout_ms, execution_id) {
                Ok(response) => response,
                Err(_) => {
                    return DispatchResult {
                        status: "ERROR".to_string(),
                        output: None,
                        dispatcher: "pool".to_string(),
                    }
                }
            };

        if http_response.status_code >= 400 {
            return DispatchResult {
                status: "ERROR".to_string(),
                output: None,
                dispatcher: "pool".to_string(),
            };
        }

        let output = if http_response.content_type.starts_with("text/plain") {
            Value::String(http_response.body)
        } else {
            serde_json::from_str::<Value>(&http_response.body)
                .unwrap_or(Value::String(http_response.body))
        };

        DispatchResult {
            status: "SUCCESS".to_string(),
            output: Some(output),
            dispatcher: "pool".to_string(),
        }
    }
}

struct SimpleHttpResponse {
    status_code: u16,
    body: String,
    content_type: String,
}

fn invoke_pool_http(
    endpoint: &str,
    payload: &Value,
    timeout_ms: u64,
    execution_id: &str,
) -> Result<SimpleHttpResponse, String> {
    let (host, port, path) = parse_http_endpoint(endpoint)?;
    let mut stream = TcpStream::connect((host.as_str(), port))
        .map_err(|err| format!("connect failed: {err}"))?;
    stream
        .set_read_timeout(Some(Duration::from_millis(timeout_ms)))
        .map_err(|err| format!("read timeout setup failed: {err}"))?;
    stream
        .set_write_timeout(Some(Duration::from_millis(timeout_ms)))
        .map_err(|err| format!("write timeout setup failed: {err}"))?;

    let body =
        serde_json::to_string(payload).map_err(|err| format!("encode payload failed: {err}"))?;
    let request = format!(
        "POST {path} HTTP/1.1\r\nHost: {host}:{port}\r\nContent-Type: application/json\r\nX-Execution-Id: {execution_id}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
        body.len(),
        body
    );
    stream
        .write_all(request.as_bytes())
        .map_err(|err| format!("request write failed: {err}"))?;
    stream
        .flush()
        .map_err(|err| format!("request flush failed: {err}"))?;

    let mut raw_response = String::new();
    stream
        .read_to_string(&mut raw_response)
        .map_err(|err| format!("response read failed: {err}"))?;
    parse_http_response(&raw_response)
}

fn parse_http_endpoint(endpoint: &str) -> Result<(String, u16, String), String> {
    let without_scheme = endpoint
        .strip_prefix("http://")
        .ok_or_else(|| "only http:// endpoints are supported".to_string())?;
    let (authority, path_part) = match without_scheme.split_once('/') {
        Some((authority, rest)) => (authority, format!("/{}", rest)),
        None => (without_scheme, "/".to_string()),
    };
    if authority.is_empty() {
        return Err("endpoint host is missing".to_string());
    }

    let (host, port) = match authority.rsplit_once(':') {
        Some((host, port_raw)) => {
            let port = port_raw
                .parse::<u16>()
                .map_err(|_| "invalid endpoint port".to_string())?;
            (host.to_string(), port)
        }
        None => (authority.to_string(), 80),
    };

    if host.is_empty() {
        return Err("endpoint host is missing".to_string());
    }

    Ok((host, port, path_part))
}

fn parse_http_response(raw_response: &str) -> Result<SimpleHttpResponse, String> {
    let (head, body) = raw_response
        .split_once("\r\n\r\n")
        .ok_or_else(|| "invalid HTTP response".to_string())?;
    let mut head_lines = head.lines();
    let status_line = head_lines
        .next()
        .ok_or_else(|| "missing HTTP status line".to_string())?;
    let status_code = status_line
        .split_whitespace()
        .nth(1)
        .ok_or_else(|| "missing HTTP status code".to_string())?
        .parse::<u16>()
        .map_err(|_| "invalid HTTP status code".to_string())?;

    let mut content_type = "application/json".to_string();
    let mut chunked = false;
    for line in head_lines {
        if let Some((name, value)) = line.split_once(':') {
            if name.trim().eq_ignore_ascii_case("content-type") {
                content_type = value.trim().to_string();
                continue;
            }
            if name.trim().eq_ignore_ascii_case("transfer-encoding")
                && value.to_ascii_lowercase().contains("chunked")
            {
                chunked = true;
            }
        }
    }

    let decoded_body = if chunked {
        decode_chunked_body(body)?
    } else {
        body.to_string()
    };

    Ok(SimpleHttpResponse {
        status_code,
        body: decoded_body,
        content_type,
    })
}

fn decode_chunked_body(raw_body: &str) -> Result<String, String> {
    let mut remaining = raw_body;
    let mut decoded = String::new();

    loop {
        let line_end = remaining.find("\r\n").ok_or_else(|| {
            "invalid chunked body: missing chunk size line terminator".to_string()
        })?;
        let size_line = &remaining[..line_end];
        let size_hex = size_line
            .split(';')
            .next()
            .ok_or_else(|| "invalid chunked body: malformed chunk size".to_string())?
            .trim();
        let size = usize::from_str_radix(size_hex, 16)
            .map_err(|_| "invalid chunked body: invalid chunk size".to_string())?;
        remaining = &remaining[line_end + 2..];

        if size == 0 {
            break;
        }
        if remaining.len() < size + 2 {
            return Err("invalid chunked body: incomplete chunk".to_string());
        }
        decoded.push_str(&remaining[..size]);
        remaining = &remaining[size..];
        if !remaining.starts_with("\r\n") {
            return Err("invalid chunked body: missing chunk terminator".to_string());
        }
        remaining = &remaining[2..];
    }

    Ok(decoded)
}

pub struct DispatcherRouter {
    local: Arc<dyn Dispatcher>,
    pool: Arc<dyn Dispatcher>,
}

impl DispatcherRouter {
    pub fn new(local: Box<dyn Dispatcher>, pool: Box<dyn Dispatcher>) -> Self {
        Self {
            local: Arc::from(local),
            pool: Arc::from(pool),
        }
    }

    pub fn dispatch(
        &self,
        function: &FunctionSpec,
        payload: &Value,
        execution_id: &str,
    ) -> DispatchResult {
        match function.execution_mode {
            ExecutionMode::Local => self.local.dispatch(function, payload, execution_id),
            ExecutionMode::Deployment | ExecutionMode::Pool => {
                self.pool.dispatch(function, payload, execution_id)
            }
        }
    }
}

impl Clone for DispatcherRouter {
    fn clone(&self) -> Self {
        Self {
            local: Arc::clone(&self.local),
            pool: Arc::clone(&self.pool),
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
