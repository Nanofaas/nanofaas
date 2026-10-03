//! mcFaas Watchdog
//!
//! Generic process supervisor for function containers.
//! Supports multiple execution modes:
//! - HTTP: Function exposes HTTP server (Java Spring, Python FastAPI)
//! - STDIO: Function reads from stdin, writes to stdout (Python scripts, Node)
//! - FILE: Function reads /tmp/input.json, writes /tmp/output.json (Bash, legacy)

mod envelope;

use axum::{
    extract::State,
    http::header,
    http::HeaderMap,
    http::StatusCode,
    response::IntoResponse,
    routing::get,
    Json, Router,
};
use nix::sys::signal::{self, Signal};
use nix::unistd::Pid;
use serde::Serialize;
use std::env;
use std::future::IntoFuture;
use std::path::Path;
use std::process::ExitCode;
use std::sync::Arc;
use std::time::Duration;
use tokio::fs;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::process::{Child, Command};
use tokio::sync::{oneshot, Mutex};
use tokio::time::{timeout, Instant};
use tracing::{debug, error, info, warn};

use prometheus_client::encoding::text::encode;
use prometheus_client::encoding::EncodeLabelSet;
use prometheus_client::metrics::counter::Counter;
use prometheus_client::metrics::family::Family;
use prometheus_client::metrics::gauge::Gauge;
use prometheus_client::metrics::histogram::{exponential_buckets, Histogram};
use prometheus_client::registry::Registry;

// ============================================================================
// Configuration
// ============================================================================

#[derive(Debug, Clone, Copy, PartialEq)]
enum ExecutionMode {
    Http,  // POST to HTTP endpoint (one-shot)
    Stdio, // stdin/stdout (one-shot)
    File,  // /tmp/input.json -> /tmp/output.json (one-shot)
}

impl ExecutionMode {
    fn parse(s: &str) -> Result<Self, String> {
        match s.to_uppercase().as_str() {
            "HTTP" => Ok(Self::Http),
            "STDIO" => Ok(Self::Stdio),
            "FILE" => Ok(Self::File),
            _ => Err(format!("EXECUTION_MODE must be HTTP, STDIO, or FILE (got {s:?})")),
        }
    }
}

fn parse_command(command: &str) -> Result<Vec<String>, String> {
    let argv = shlex::split(command)
        .ok_or_else(|| "WATCHDOG_CMD contains invalid shell quoting".to_string())?;
    if argv.is_empty() {
        return Err("WATCHDOG_CMD must not be empty".to_string());
    }
    Ok(argv)
}

fn parse_u64(name: &str, value: &str) -> Result<u64, String> {
    value
        .parse()
        .map_err(|_| format!("{name} must be an unsigned integer"))
}

fn parse_u16(name: &str, value: &str) -> Result<u16, String> {
    value
        .parse()
        .map_err(|_| format!("{name} must be an unsigned 16-bit integer"))
}

#[derive(Debug, Clone)]
struct Config {
    /// Warm (deployment-style) mode: expose /invoke and execute per request.
    warm: bool,
    /// URL for callback to control plane
    callback_url: Option<String>,
    /// Execution ID (one-shot only)
    execution_id: Option<String>,
    /// Timeout in milliseconds
    timeout_ms: u64,
    /// Trace ID for distributed tracing
    trace_id: Option<String>,
    /// Command to run (the function process)
    command: Vec<String>,
    /// Execution mode: HTTP, STDIO, or FILE
    mode: ExecutionMode,
    /// Runtime HTTP endpoint (for HTTP mode)
    runtime_url: String,
    /// Health check endpoint (for HTTP mode, optional)
    health_url: Option<String>,
    /// Max time to wait for process to be ready (ms)
    ready_timeout_ms: u64,
    /// Input file path (for FILE mode)
    input_file: String,
    /// Output file path (for FILE mode)
    output_file: String,
    /// Port for warm HTTP server (when warm=true)
    warm_port: u16,
}

impl Config {
    fn from_env() -> Result<Self, String> {
        let warm = env::var("WARM")
            .ok()
            .map(|v| matches!(v.as_str(), "1" | "true" | "TRUE" | "yes" | "YES"))
            .unwrap_or(false);

        let callback_url = env::var("CALLBACK_URL").ok();
        let execution_id = env::var("EXECUTION_ID").ok();

        let timeout_ms = parse_u64(
            "TIMEOUT_MS",
            &env::var("TIMEOUT_MS").unwrap_or_else(|_| "30000".to_string()),
        )?;

        let trace_id = env::var("TRACE_ID").ok();

        let command = parse_command(
            &env::var("WATCHDOG_CMD").unwrap_or_else(|_| "java -jar /app/app.jar".to_string()),
        )?;

        let mode = ExecutionMode::parse(
            &env::var("EXECUTION_MODE").unwrap_or_else(|_| "HTTP".to_string())
        )?;

        // In warm mode, watchdog typically binds to 8080. Default the internal runtime to 8081
        // to avoid port conflicts when mode=HTTP and the runtime is an internal server.
        let default_runtime_url = if warm {
            "http://127.0.0.1:8081/invoke"
        } else {
            "http://127.0.0.1:8080/invoke"
        };

        let runtime_url = env::var("RUNTIME_URL")
            .unwrap_or_else(|_| default_runtime_url.to_string());

        let health_url = env::var("HEALTH_URL").ok();

        let ready_timeout_ms = parse_u64(
            "READY_TIMEOUT_MS",
            &env::var("READY_TIMEOUT_MS").unwrap_or_else(|_| "10000".to_string()),
        )?;

        let input_file = env::var("INPUT_FILE")
            .unwrap_or_else(|_| "/tmp/input.json".to_string());

        let output_file = env::var("OUTPUT_FILE")
            .unwrap_or_else(|_| "/tmp/output.json".to_string());

        let warm_port = parse_u16(
            "WARM_PORT",
            &env::var("WARM_PORT").unwrap_or_else(|_| "8080".to_string()),
        )?;

        Ok(Config {
            warm,
            callback_url,
            execution_id,
            timeout_ms,
            trace_id,
            command,
            mode,
            runtime_url,
            health_url,
            ready_timeout_ms,
            input_file,
            output_file,
            warm_port,
        })
    }
}

// ============================================================================
// Callback DTOs
// ============================================================================

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct InvocationResult {
    success: bool,
    output: Option<serde_json::Value>,
    error: Option<ErrorInfo>,
    #[serde(skip_serializing_if = "Option::is_none")]
    status_code: Option<u16>,
    #[serde(skip_serializing_if = "Option::is_none")]
    headers: Option<std::collections::BTreeMap<String, String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    encoding: Option<String>,
}

#[derive(Debug, Serialize)]
struct ErrorInfo {
    code: String,
    message: String,
}

impl InvocationResult {
    fn success(output: serde_json::Value) -> Self {
        Self {
            success: true,
            output: Some(output),
            error: None,
            status_code: None,
            headers: None,
            encoding: None,
        }
    }

    fn success_with_envelope(envelope: envelope::Envelope) -> Self {
        Self {
            success: true,
            output: Some(envelope.output),
            error: None,
            status_code: Some(envelope.status_code),
            headers: (!envelope.headers.is_empty()).then_some(envelope.headers),
            encoding: envelope.encoding,
        }
    }

    fn error(code: &str, message: &str) -> Self {
        Self {
            success: false,
            output: None,
            error: Some(ErrorInfo {
                code: code.to_string(),
                message: message.to_string(),
            }),
            status_code: None,
            headers: None,
            encoding: None,
        }
    }
}

// ============================================================================
// Warm Mode (deployment-style)
// ============================================================================

#[derive(Clone)]
struct WarmAppState {
    config: Arc<Config>,
    // Warm containers handle one invocation at a time (OpenWhisk-style).
    invoke_lock: Arc<Mutex<()>>,
    metrics: Arc<WatchdogMetrics>,
    function_name: String,
}

// ============================================================================
// Prometheus Metrics (warm mode)
// ============================================================================

#[derive(Debug, Clone, Hash, PartialEq, Eq, EncodeLabelSet)]
struct InvocationsLabels {
    function: String,
    mode: String,
    success: String,
}

#[derive(Debug, Clone, Hash, PartialEq, Eq, EncodeLabelSet)]
struct DurationLabels {
    function: String,
    mode: String,
}

#[derive(Debug, Clone, Hash, PartialEq, Eq, EncodeLabelSet)]
struct FunctionLabel {
    function: String,
}

#[derive(Debug, Clone, Hash, PartialEq, Eq, EncodeLabelSet)]
struct TimeoutsLabels {
    function: String,
    mode: String,
}

struct WatchdogMetrics {
    registry: Registry,
    invocations_total: Family<InvocationsLabels, Counter>,
    invocation_duration_seconds: Family<DurationLabels, Histogram>,
    invocations_in_flight: Family<FunctionLabel, Gauge>,
    timeouts_total: Family<TimeoutsLabels, Counter>,
}

impl WatchdogMetrics {
    fn new() -> Self {
        let invocations_total = Family::<InvocationsLabels, Counter>::default();
        let invocation_duration_seconds = Family::<DurationLabels, Histogram>::new_with_constructor(|| {
            // Seconds; from ~5ms to ~40s.
            Histogram::new(exponential_buckets(0.005, 2.0, 14))
        });
        let invocations_in_flight = Family::<FunctionLabel, Gauge>::default();
        let timeouts_total = Family::<TimeoutsLabels, Counter>::default();

        let mut registry = Registry::default();
        registry.register(
            // prometheus-client appends "_total" to Counter names, so avoid duplicating it here.
            "watchdog_invocations",
            "Total invocations handled by the watchdog",
            invocations_total.clone(),
        );
        registry.register(
            "watchdog_invocation_duration_seconds",
            "Invocation duration in seconds (watchdog)",
            invocation_duration_seconds.clone(),
        );
        registry.register(
            "watchdog_invocations_in_flight",
            "In-flight invocations (watchdog)",
            invocations_in_flight.clone(),
        );
        registry.register(
            // prometheus-client appends "_total" to Counter names, so avoid duplicating it here.
            "watchdog_timeouts",
            "Invocation timeouts (watchdog)",
            timeouts_total.clone(),
        );

        Self {
            registry,
            invocations_total,
            invocation_duration_seconds,
            invocations_in_flight,
            timeouts_total,
        }
    }

    fn render(&self) -> Result<String, std::fmt::Error> {
        let mut buf = String::new();
        encode(&mut buf, &self.registry)?;
        Ok(buf)
    }
}

// ============================================================================
// Process Management
// ============================================================================

async fn terminate_process_group(child: &mut Child) {
    let Some(pid) = child.id() else {
        return;
    };

    let process_group = Pid::from_raw(pid as i32);
    let _ = signal::killpg(process_group, Signal::SIGTERM);
    if timeout(Duration::from_millis(100), child.wait()).await.is_err() {
        let _ = signal::killpg(process_group, Signal::SIGKILL);
        let _ = child.wait().await;
    }
}

async fn read_pipe<R>(mut pipe: R) -> Result<Vec<u8>, String>
where
    R: tokio::io::AsyncRead + Unpin,
{
    let mut output = Vec::new();
    pipe.read_to_end(&mut output)
        .await
        .map_err(|e| format!("Failed to read process output: {e}"))?;
    Ok(output)
}

// ============================================================================
// HTTP Mode
// ============================================================================

async fn spawn_http_runtime(config: &Config) -> Result<Child, String> {
    if config.command.is_empty() {
        return Err("No command specified".to_string());
    }

    let (program, args) = config.command.split_first().unwrap();
    info!(command = %program, mode = "HTTP", "Starting function runtime");

    let mut cmd = Command::new(program);
    cmd.args(args);

    if let Some(ref execution_id) = config.execution_id {
        cmd.env("EXECUTION_ID", execution_id);
    }

    // In warm mode the watchdog binds to WARM_PORT (default 8080), so the internal runtime
    // should bind to a different port (default 8081).
    let runtime_port = if config.warm { "8081" } else { "8080" };
    cmd.env("PORT", runtime_port)
        .env("SERVER_PORT", runtime_port)
        .process_group(0);

    cmd.spawn()
        .map_err(|e| format!("Failed to spawn runtime: {}", e))
}

async fn wait_for_http_ready(config: &Config) -> Result<(), String> {
    let client = reqwest::Client::new();
    let health_url = config.health_url.clone().unwrap_or_else(|| {
        config.runtime_url.replace("/invoke", "/health")
    });

    let start = Instant::now();
    let max_wait = Duration::from_millis(config.ready_timeout_ms);
    let check_interval = Duration::from_millis(50);

    debug!(url = %health_url, "Waiting for runtime to be ready");

    while start.elapsed() < max_wait {
        match client.get(&health_url)
            .timeout(Duration::from_millis(200))
            .send()
            .await
        {
            Ok(resp) if resp.status().is_success() => {
                info!(elapsed_ms = start.elapsed().as_millis(), "Runtime ready");
                return Ok(());
            }
            Ok(resp) => {
                debug!(status = %resp.status(), "Health check returned non-success");
            }
            Err(e) => {
                debug!(error = %e, "Health check failed");
            }
        }
        tokio::time::sleep(check_interval).await;
    }

    Err(format!("Runtime not ready after {}ms", config.ready_timeout_ms))
}

async fn invoke_http(
    config: &Config,
    payload: &serde_json::Value,
) -> Result<serde_json::Value, String> {
    let client = reqwest::Client::new();

    debug!(url = %config.runtime_url, "Invoking function via HTTP");

    let response = client
        .post(&config.runtime_url)
        .json(payload)
        .timeout(Duration::from_millis(config.timeout_ms))
        .send()
        .await
        .map_err(|e| format!("HTTP error: {}", e))?;

    if response.status().is_success() {
        response
            .json()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))
    } else {
        let status = response.status();
        let body = response.text().await.unwrap_or_default();
        Err(format!("Runtime error {}: {}", status, body))
    }
}

// ============================================================================
// STDIO Mode
// ============================================================================

async fn run_stdio_warm(
    config: &Config,
    payload: &serde_json::Value,
    execution_id: &str,
    trace_id: Option<&str>,
) -> Result<serde_json::Value, String> {
    let deadline = Instant::now() + Duration::from_millis(config.timeout_ms);
    if config.command.is_empty() {
        return Err("No command specified".to_string());
    }
    let payload_str = serde_json::to_string(payload)
        .map_err(|e| format!("Failed to serialize payload: {e}"))?;
    let (program, args) = config.command.split_first().unwrap();
    info!(command = %program, mode = "STDIO", "Running function");

    let mut command = Command::new(program);
    command
        .args(args)
        .env("EXECUTION_ID", execution_id)
        .env("TRACE_ID", trace_id.unwrap_or(""))
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .kill_on_drop(true)
        .process_group(0);
    let mut child = command.spawn().map_err(|e| format!("Failed to spawn process: {e}"))?;
    // Child::id becomes None after wait completes. Descendants may still hold pipes,
    // so retain the group id until the entire exchange has finished.
    let process_group = Pid::from_raw(child.id().unwrap() as i32);
    let mut stdin = child.stdin.take().ok_or("Failed to capture process stdin")?;
    let stdout = child.stdout.take().ok_or("Failed to capture process stdout")?;
    let stderr = child.stderr.take().ok_or("Failed to capture process stderr")?;

    let exchange = async {
        let write_input = async {
            stdin.write_all(payload_str.as_bytes()).await
                .map_err(|e| format!("Failed to write to stdin: {e}"))?;
            drop(stdin); // EOF lets a child reading the whole request proceed.
            Ok::<(), String>(())
        };
        let wait = async { child.wait().await.map_err(|e| format!("Process error: {e}")) };
        tokio::try_join!(write_input, wait, read_pipe(stdout), read_pipe(stderr))
    };
    // No spawned I/O tasks: dropping the timed-out exchange closes all pipes.
    let (_, status, stdout, stderr) = match tokio::time::timeout_at(deadline, exchange).await {
        Ok(Ok(output)) => output,
        failure => {
            let _ = signal::killpg(process_group, Signal::SIGKILL);
            let _ = child.wait().await;
            return Err(match failure {
                Ok(Err(error)) => error,
                Err(_) => "Process timed out".to_string(),
                Ok(Ok(_)) => unreachable!(),
            });
        }
    };

    if !status.success() {
        return Err(format!("Process exited with {}: {}", status, String::from_utf8_lossy(&stderr)));
    }
    let stdout = String::from_utf8_lossy(&stdout);
    serde_json::from_str(&stdout).map_err(|e| format!("Invalid JSON output: {} (raw: {})", e, stdout.trim()))
}

// ============================================================================
// FILE Mode
// ============================================================================

async fn run_file_warm(
    config: &Config,
    payload: &serde_json::Value,
    execution_id: &str,
    trace_id: Option<&str>,
) -> Result<serde_json::Value, String> {
    // Write input file
    let payload_str = serde_json::to_string_pretty(payload)
        .map_err(|e| format!("Failed to serialize payload: {}", e))?;

    fs::write(&config.input_file, &payload_str).await
        .map_err(|e| format!("Failed to write input file: {}", e))?;

    info!(input = %config.input_file, mode = "FILE", "Input file written");

    // Remove output file if it exists
    let _ = fs::remove_file(&config.output_file).await;

    if config.command.is_empty() {
        return Err("No command specified".to_string());
    }

    let (program, args) = config.command.split_first().unwrap();
    info!(command = %program, "Running function");

    let mut command = Command::new(program);
    command
        .args(args)
        .env("EXECUTION_ID", execution_id)
        .env("TRACE_ID", trace_id.unwrap_or(""))
        .env("INPUT_FILE", &config.input_file)
        .env("OUTPUT_FILE", &config.output_file)
        .process_group(0);
    let mut child = command
        .spawn()
        .map_err(|e| format!("Failed to spawn process: {}", e))?;

    // Wait for process with timeout
    let status = match timeout(Duration::from_millis(config.timeout_ms), child.wait()).await {
        Ok(status) => status.map_err(|e| format!("Process error: {e}"))?,
        Err(_) => {
            terminate_process_group(&mut child).await;
            return Err("Process timed out".to_string());
        }
    };

    if !status.success() {
        return Err(format!("Process exited with {}", status));
    }

    // Read output file
    if !Path::new(&config.output_file).exists() {
        return Err(format!("Output file {} not created", config.output_file));
    }

    let output_str = fs::read_to_string(&config.output_file).await
        .map_err(|e| format!("Failed to read output file: {}", e))?;

    serde_json::from_str(&output_str)
        .map_err(|e| format!("Invalid JSON in output file: {}", e))
}

// ============================================================================
// Callback
// ============================================================================

async fn send_callback(config: &Config, result: InvocationResult) -> Result<(), String> {
    let callback_url = config.callback_url.as_ref().ok_or("CALLBACK_URL not set")?;
    let execution_id = config.execution_id.as_ref().ok_or("EXECUTION_ID not set")?;
    let client = reqwest::Client::new();

    let url = if callback_url.ends_with(":complete") {
        callback_url.clone()
    } else {
        format!("{}/{}:complete", callback_url, execution_id)
    };

    info!(url = %url, success = result.success, "Sending callback");

    let mut request = client
        .post(&url)
        .json(&result)
        .timeout(Duration::from_secs(10));

    if let Some(ref trace_id) = config.trace_id {
        request = request.header("X-Trace-Id", trace_id);
    }

    // Retry logic: 3 attempts with backoff
    let delays = [100u64, 500, 2000];
    let mut last_error = String::new();

    for (attempt, delay_ms) in delays.iter().enumerate() {
        match request.try_clone().unwrap().send().await {
            Ok(resp) if resp.status().is_success() => {
                info!(attempt = attempt + 1, "Callback sent successfully");
                return Ok(());
            }
            Ok(resp) => {
                last_error = format!("Callback returned {}", resp.status());
                warn!(attempt = attempt + 1, error = %last_error, "Callback failed");
            }
            Err(e) => {
                last_error = format!("Callback error: {}", e);
                warn!(attempt = attempt + 1, error = %last_error, "Callback failed");
            }
        }

        if attempt < delays.len() - 1 {
            tokio::time::sleep(Duration::from_millis(*delay_ms)).await;
        }
    }

    Err(last_error)
}

// ============================================================================
// Warm Mode (deployment-style)
// ============================================================================

async fn warm_health() -> StatusCode {
    StatusCode::OK
}

async fn warm_metrics(State(state): State<WarmAppState>) -> impl IntoResponse {
    match state.metrics.render() {
        Ok(body) => (
            StatusCode::OK,
            [(header::CONTENT_TYPE, "text/plain; version=0.0.4; charset=utf-8")],
            body,
        )
            .into_response(),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"error": format!("metrics encode failed: {e}")})),
        )
            .into_response(),
    }
}

async fn warm_invoke_get_ready() -> StatusCode {
    // K8s readiness probes in this repo use GET /invoke.
    StatusCode::OK
}

struct ProxiedResponse {
    status: StatusCode,
    headers: HeaderMap,
    body: Vec<u8>,
}

enum WarmOutput {
    Local(serde_json::Value),
    Proxied(ProxiedResponse),
}

async fn warm_invoke(
    State(state): State<WarmAppState>,
    headers: HeaderMap,
    Json(payload): Json<serde_json::Value>,
) -> axum::response::Response {
    let execution_id = headers
        .get("x-execution-id")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");

    if execution_id.is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"error": "X-Execution-Id header is required"})),
        )
            .into_response();
    }

    let trace_id = headers
        .get("x-trace-id")
        .and_then(|v| v.to_str().ok());

    // Ensure a single in-flight invocation per warm container.
    let _guard = state.invoke_lock.lock().await;

    let mode_str = match state.config.mode {
        ExecutionMode::Http => "HTTP",
        ExecutionMode::Stdio => "STDIO",
        ExecutionMode::File => "FILE",
    };

    // Metrics: in-flight + duration + totals
    let start = Instant::now();
    state
        .metrics
        .invocations_in_flight
        .get_or_create(&FunctionLabel {
            function: state.function_name.clone(),
        })
        .inc();

    let out = match state.config.mode {
        ExecutionMode::Http => invoke_http_warm(&state.config, &payload, execution_id, trace_id).await,
        ExecutionMode::Stdio => run_stdio_warm(&state.config, &payload, execution_id, trace_id)
            .await
            .map(WarmOutput::Local),
        ExecutionMode::File => run_file_warm(&state.config, &payload, execution_id, trace_id)
            .await
            .map(WarmOutput::Local),
    };

    let elapsed = start.elapsed().as_secs_f64();
    state
        .metrics
        .invocation_duration_seconds
        .get_or_create(&DurationLabels {
            function: state.function_name.clone(),
            mode: mode_str.to_string(),
        })
        .observe(elapsed);

    state
        .metrics
        .invocations_in_flight
        .get_or_create(&FunctionLabel {
            function: state.function_name.clone(),
        })
        .dec();

    finish_invocation(out, &state, mode_str, execution_id)
}

fn finish_invocation(
    out: Result<WarmOutput, String>,
    state: &WarmAppState,
    mode_str: &str,
    execution_id: &str,
) -> axum::response::Response {
    match out {
        Ok(output) => success_response(output, state, mode_str, execution_id),
        Err(e) => error_response(e, state, mode_str),
    }
}

fn success_response(
    output: WarmOutput,
    state: &WarmAppState,
    mode_str: &str,
    execution_id: &str,
) -> axum::response::Response {
    state
        .metrics
        .invocations_total
        .get_or_create(&InvocationsLabels {
            function: state.function_name.clone(),
            mode: mode_str.to_string(),
            success: "true".to_string(),
        })
        .inc();
    match output {
        WarmOutput::Proxied(proxied) => proxied_response(proxied),
        WarmOutput::Local(value) => local_response(value, execution_id),
    }
}

fn proxied_response(proxied: ProxiedResponse) -> axum::response::Response {
    let mut response = axum::response::Response::builder()
        .status(proxied.status)
        .body(axum::body::Body::from(proxied.body))
        .unwrap();
    response.headers_mut().extend(proxied.headers);
    response
}

fn local_response(value: serde_json::Value, execution_id: &str) -> axum::response::Response {
    match envelope::detect(&value) {
        None => (StatusCode::OK, Json(value)).into_response(),
        Some(Err(message)) => {
            warn!(execution_id = %execution_id, error = %message,
                "Envelope rejected, treating as platform error");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({"error": message})),
            )
                .into_response()
        }
        Some(Ok(envelope)) => envelope_response(envelope),
    }
}

fn envelope_response(envelope: envelope::Envelope) -> axum::response::Response {
    let mut response = (
        StatusCode::from_u16(envelope.status_code)
            .unwrap_or(StatusCode::INTERNAL_SERVER_ERROR),
        Json(envelope.output),
    )
        .into_response();
    let headers = response.headers_mut();
    for (name, value) in &envelope.headers {
        if let (Ok(name), Ok(value)) = (
            name.parse::<axum::http::HeaderName>(),
            value.parse::<axum::http::HeaderValue>(),
        ) {
            headers.insert(name, value);
        }
    }
    headers.insert(
        envelope::MARKER_HEADER
            .parse::<axum::http::HeaderName>()
            .unwrap(),
        axum::http::HeaderValue::from_static("true"),
    );
    if let Some(encoding) = envelope.encoding.as_deref() {
        if let Ok(value) = encoding.parse::<axum::http::HeaderValue>() {
            headers.insert(
                envelope::ENCODING_HEADER
                    .parse::<axum::http::HeaderName>()
                    .unwrap(),
                value,
            );
        }
    }
    response
}

fn error_response(e: String, state: &WarmAppState, mode_str: &str) -> axum::response::Response {
    if is_timeout_error(&e) {
        state
            .metrics
            .timeouts_total
            .get_or_create(&TimeoutsLabels {
                function: state.function_name.clone(),
                mode: mode_str.to_string(),
            })
            .inc();
    }
    state
        .metrics
        .invocations_total
        .get_or_create(&InvocationsLabels {
            function: state.function_name.clone(),
            mode: mode_str.to_string(),
            success: "false".to_string(),
        })
        .inc();
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        Json(serde_json::json!({"error": e})),
    )
        .into_response()
}

fn is_timeout_error(e: &str) -> bool {
    let s = e.to_lowercase();
    s.contains("timed out") || s.contains("timeout")
}

async fn invoke_http_warm(
    config: &Config,
    payload: &serde_json::Value,
    execution_id: &str,
    trace_id: Option<&str>,
) -> Result<WarmOutput, String> {
    let client = reqwest::Client::new();
    let mut req = client
        .post(&config.runtime_url)
        .header("X-Execution-Id", execution_id)
        .json(payload)
        .timeout(Duration::from_millis(config.timeout_ms));

    if let Some(t) = trace_id {
        req = req.header("X-Trace-Id", t);
    }

    let response = req
        .send()
        .await
        .map_err(|e| format!("HTTP error: {}", e))?;

    let status = response.status();
    let function_decided = response
        .headers()
        .get(envelope::MARKER_HEADER)
        .and_then(|value| value.to_str().ok())
        == Some("true");

    if function_decided {
        return Ok(WarmOutput::Proxied(ProxiedResponse {
            status: StatusCode::from_u16(status.as_u16())
                .map_err(|e| format!("Invalid runtime status {status}: {e}"))?,
            headers: response.headers().clone(),
            body: response
                .bytes()
                .await
                .map_err(|e| format!("Failed to read response: {e}"))?
                .to_vec(),
        }));
    }

    if status.is_success() {
        response
            .json()
            .await
            .map(WarmOutput::Local)
            .map_err(|e| format!("Failed to parse response: {}", e))
    } else {
        let body = response.text().await.unwrap_or_default();
        Err(format!("Runtime error {}: {}", status, body))
    }
}

async fn shutdown_signal() {
    #[cfg(unix)]
    {
        let mut terminate = tokio::signal::unix::signal(
            tokio::signal::unix::SignalKind::terminate(),
        )
        .expect("failed to install SIGTERM handler");
        tokio::select! {
            _ = tokio::signal::ctrl_c() => {}
            _ = terminate.recv() => {}
        }
    }

    #[cfg(not(unix))]
    tokio::signal::ctrl_c().await.ok();

    info!("Shutdown signal received");
}

async fn execute_warm_server(config: Config) -> ExitCode {
    info!(port = config.warm_port, mode = ?config.mode, "Starting warm server");

    // If warm mode is HTTP, spawn the internal runtime once and keep it alive.
    let mut child = if config.mode == ExecutionMode::Http {
        let mut c = match spawn_http_runtime(&config).await {
            Ok(c) => c,
            Err(e) => {
                error!(error = %e, "Failed to spawn runtime");
                return ExitCode::from(1);
            }
        };

        if let Err(e) = wait_for_http_ready(&config).await {
            error!(error = %e, "Runtime failed to start");
            terminate_process_group(&mut c).await;
            return ExitCode::from(1);
        }
        Some(c)
    } else {
        None
    };

    let state = WarmAppState {
        config: Arc::new(config.clone()),
        invoke_lock: Arc::new(Mutex::new(())),
        metrics: Arc::new(WatchdogMetrics::new()),
        function_name: env::var("FUNCTION_NAME").unwrap_or_else(|_| "unknown".to_string()),
    };

    let app = Router::new()
        .route("/health", get(warm_health))
        .route("/metrics", get(warm_metrics))
        .route("/invoke", get(warm_invoke_get_ready).post(warm_invoke))
        .with_state(state);

    let addr = std::net::SocketAddr::from(([0, 0, 0, 0], config.warm_port));
    let listener = match tokio::net::TcpListener::bind(addr).await {
        Ok(l) => l,
        Err(e) => {
            error!(error = %e, "Failed to bind to port");
            if let Some(ref mut c) = child {
                terminate_process_group(c).await;
            }
            return ExitCode::from(1);
        }
    };

    info!(addr = %addr, "Warm server listening");

    let (shutdown_tx, shutdown_rx) = oneshot::channel();
    let server = axum::serve(listener, app)
        .with_graceful_shutdown(async move {
            let _ = shutdown_rx.await;
        })
        .into_future();
    tokio::pin!(server);
    let mut shutdown_tx = Some(shutdown_tx);

    let exit_code = if let Some(runtime) = child.as_mut() {
        tokio::select! {
            server_result = &mut server => {
                if let Err(e) = server_result {
                    error!(error = %e, "Server error");
                }
                terminate_process_group(runtime).await;
                ExitCode::from(1)
            }
            runtime_result = runtime.wait() => {
                match runtime_result {
                    Ok(status) => error!(%status, "HTTP runtime exited unexpectedly"),
                    Err(e) => error!(error = %e, "Failed while waiting for HTTP runtime"),
                }
                let _ = shutdown_tx.take().unwrap().send(());
                if let Err(e) = (&mut server).await {
                    error!(error = %e, "Server error during runtime shutdown");
                }
                ExitCode::from(1)
            }
            _ = shutdown_signal() => {
                let _ = shutdown_tx.take().unwrap().send(());
                info!("Shutting down runtime");
                terminate_process_group(runtime).await;
                if let Err(e) = (&mut server).await {
                    error!(error = %e, "Server error during shutdown");
                    ExitCode::from(1)
                } else {
                    ExitCode::SUCCESS
                }
            }
        }
    } else {
        tokio::select! {
            server_result = &mut server => {
                if let Err(e) = server_result {
                    error!(error = %e, "Server error");
                }
                ExitCode::from(1)
            }
            _ = shutdown_signal() => {
                let _ = shutdown_tx.take().unwrap().send(());
                if let Err(e) = (&mut server).await {
                    error!(error = %e, "Server error during shutdown");
                    ExitCode::from(1)
                } else {
                    ExitCode::SUCCESS
                }
            }
        }
    };

    info!("Warm server shutdown complete");
    exit_code
}

// ============================================================================
// Main
// ============================================================================

#[tokio::main(flavor = "current_thread")]
async fn main() -> ExitCode {
    // Minimal CLI support (used by integration tests and container probes).
    if env::args().any(|a| a == "--version" || a == "-V") {
        println!("nanofaas-watchdog {}", env!("CARGO_PKG_VERSION"));
        return ExitCode::SUCCESS;
    }
    if env::args().any(|a| a == "--help" || a == "-h") {
        println!("nanofaas-watchdog");
        println!();
        println!("Environment-driven watchdog for nanofaas function containers.");
        println!();
        println!("Flags:");
        println!("  --help, -h       Show this help");
        println!("  --version, -V    Print version");
        return ExitCode::SUCCESS;
    }

    // Initialize tracing
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::from_default_env()
                .add_directive(tracing::Level::INFO.into()),
        )
        .json()
        .init();

    info!(version = env!("CARGO_PKG_VERSION"), "mcFaas Watchdog starting");

    // Load configuration
    let config = match Config::from_env() {
        Ok(c) => c,
        Err(e) => {
            error!(error = %e, "Configuration error");
            return ExitCode::from(1);
        }
    };

    info!(
        timeout_ms = config.timeout_ms,
        mode = ?config.mode,
        warm = config.warm,
        "Configuration loaded"
    );

    if config.warm {
        return execute_warm_server(config).await;
    }

    // One-shot mode requires callback URL + execution id.
    if config.callback_url.is_none() || config.execution_id.is_none() {
        error!("CALLBACK_URL and EXECUTION_ID are required when WARM is not enabled");
        return ExitCode::from(1);
    }

    // Parse invocation payload
    let payload: serde_json::Value = match env::var("INVOCATION_PAYLOAD") {
        Ok(p) => serde_json::from_str(&p).unwrap_or(serde_json::Value::Null),
        Err(_) => serde_json::Value::Null,
    };

    // Execute based on mode
    let result = match config.mode {
        ExecutionMode::Http => {
            execute_http_mode(&config, &payload).await
        }
        ExecutionMode::Stdio => {
            execute_stdio_mode(&config, &payload).await
        }
        ExecutionMode::File => {
            execute_file_mode(&config, &payload).await
        }
    };

    // Send callback
    if let Err(e) = send_callback(&config, result).await {
        error!(error = %e, "Failed to send callback after all retries");
        return ExitCode::from(1);
    }

    info!("Watchdog exiting");
    ExitCode::SUCCESS
}

async fn execute_http_mode(config: &Config, payload: &serde_json::Value) -> InvocationResult {
    // Spawn runtime
    let mut child = match spawn_http_runtime(config).await {
        Ok(c) => c,
        Err(e) => {
            error!(error = %e, "Failed to spawn runtime");
            return InvocationResult::error("SPAWN_ERROR", &e);
        }
    };

    // Wait for ready
    if let Err(e) = wait_for_http_ready(config).await {
        error!(error = %e, "Runtime failed to start");
        terminate_process_group(&mut child).await;
        return InvocationResult::error("STARTUP_ERROR", &e);
    }

    // Invoke with timeout
    let invoke_result = timeout(
        Duration::from_millis(config.timeout_ms),
        invoke_http(config, payload)
    ).await;

    // Cleanup
    terminate_process_group(&mut child).await;

    match invoke_result {
        Ok(Ok(output)) => {
            info!("Function executed successfully");
            InvocationResult::success(output)
        }
        Ok(Err(e)) if e.to_lowercase().contains("timed out") || e.to_lowercase().contains("timeout") => {
            error!(timeout_ms = config.timeout_ms, error = %e, "Function timed out");
            InvocationResult::error(
                "TIMEOUT",
                &format!("Function exceeded timeout of {}ms", config.timeout_ms),
            )
        }
        Ok(Err(e)) => {
            error!(error = %e, "Function execution failed");
            InvocationResult::error("FUNCTION_ERROR", &e)
        }
        Err(_) => {
            error!(timeout_ms = config.timeout_ms, "Function timed out");
            InvocationResult::error(
                "TIMEOUT",
                &format!("Function exceeded timeout of {}ms", config.timeout_ms),
            )
        }
    }
}

async fn execute_stdio_mode(config: &Config, payload: &serde_json::Value) -> InvocationResult {
    let execution_id = config.execution_id.as_deref().unwrap_or("");
    match run_stdio_warm(config, payload, execution_id, config.trace_id.as_deref()).await {
        Ok(output) => match envelope::detect(&output) {
            None => {
                info!("Function executed successfully");
                InvocationResult::success(output)
            }
            Some(Ok(envelope)) => InvocationResult::success_with_envelope(envelope),
            Some(Err(message)) => InvocationResult::error("OUTPUT_SERIALIZATION_ERROR", &message),
        },
        Err(e) if e.contains("timed out") => {
            error!(timeout_ms = config.timeout_ms, "Function timed out");
            InvocationResult::error("TIMEOUT", &e)
        }
        Err(e) => {
            error!(error = %e, "Function execution failed");
            InvocationResult::error("FUNCTION_ERROR", &e)
        }
    }
}

async fn execute_file_mode(config: &Config, payload: &serde_json::Value) -> InvocationResult {
    let execution_id = config.execution_id.as_deref().unwrap_or("");
    match run_file_warm(config, payload, execution_id, config.trace_id.as_deref()).await {
        Ok(output) => match envelope::detect(&output) {
            None => {
                info!("Function executed successfully");
                InvocationResult::success(output)
            }
            Some(Ok(envelope)) => InvocationResult::success_with_envelope(envelope),
            Some(Err(message)) => InvocationResult::error("OUTPUT_SERIALIZATION_ERROR", &message),
        },
        Err(e) if e.contains("timed out") => {
            error!(timeout_ms = config.timeout_ms, "Function timed out");
            InvocationResult::error("TIMEOUT", &e)
        }
        Err(e) => {
            error!(error = %e, "Function execution failed");
            InvocationResult::error("FUNCTION_ERROR", &e)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn stdio_config(output: serde_json::Value) -> Config {
        Config {
            warm: true,
            callback_url: None,
            execution_id: None,
            timeout_ms: 1_000,
            trace_id: None,
            command: vec![
                "sh".to_string(),
                "-c".to_string(),
                "cat >/dev/null; printf '%s' \"$1\"".to_string(),
                "sh".to_string(),
                output.to_string(),
            ],
            mode: ExecutionMode::Stdio,
            runtime_url: "http://127.0.0.1:0".to_string(),
            health_url: None,
            ready_timeout_ms: 1_000,
            input_file: "/tmp/input.json".to_string(),
            output_file: "/tmp/output.json".to_string(),
            warm_port: 0,
        }
    }

    async fn invoke_stdio_returning(output: serde_json::Value) -> axum::response::Response {
        let state = WarmAppState {
            config: Arc::new(stdio_config(output)),
            invoke_lock: Arc::new(Mutex::new(())),
            metrics: Arc::new(WatchdogMetrics::new()),
            function_name: "test".to_string(),
        };
        let mut headers = HeaderMap::new();
        headers.insert("x-execution-id", "exec-1".parse().unwrap());

        warm_invoke(
            State(state),
            headers,
            Json(serde_json::json!({"input": "payload"})),
        )
        .await
        .into_response()
    }

    fn stdio_deadline_config(script: &str) -> Config {
        let mut config = stdio_config(serde_json::Value::Null);
        config.timeout_ms = 200;
        config.command = vec!["python3".into(), "-c".into(), script.into()];
        config
    }

    async fn assert_stdio_deadline_in_both_modes(script: &str, payload: serde_json::Value) {
        let config = stdio_deadline_config(script);
        let start = Instant::now();
        let result = execute_stdio_mode(&config, &payload).await;
        assert!(start.elapsed() < Duration::from_millis(700), "one-shot deadline excluded I/O");
        assert!(!result.success);
        assert_eq!(result.error.unwrap().code, "TIMEOUT");

        let state = WarmAppState {
            config: Arc::new(config),
            invoke_lock: Arc::new(Mutex::new(())),
            metrics: Arc::new(WatchdogMetrics::new()),
            function_name: "deadline".into(),
        };
        let mut headers = HeaderMap::new();
        headers.insert("x-execution-id", "blocked".parse().unwrap());
        let start = Instant::now();
        let response = warm_invoke(State(state.clone()), headers, Json(payload)).await;
        assert!(start.elapsed() < Duration::from_millis(700), "warm deadline excluded I/O");
        assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
        let body = axum::body::to_bytes(response.into_body(), usize::MAX).await.unwrap();
        assert!(String::from_utf8_lossy(&body).contains("Process timed out"));
        assert!(state.invoke_lock.try_lock().is_ok(), "timeout retained warm invocation lock");

        let mut headers = HeaderMap::new();
        headers.insert("x-execution-id", "next".parse().unwrap());
        let response = warm_invoke(State(state), headers, Json(serde_json::json!({"input":"ok"}))).await;
        assert_eq!(response.status(), StatusCode::OK, "next warm invocation did not recover");
    }

    #[tokio::test]
    async fn stdio_deadline_includes_blocked_stdin_in_both_modes() {
        assert_stdio_deadline_in_both_modes(
            r#"import os,sys,time
if os.environ.get("EXECUTION_ID") == "next":
    sys.stdin.read()
    print("null")
else:
    time.sleep(1)
    print("null")
"#,
            serde_json::json!({"input": "x".repeat(1_000_000)}),
        ).await;
    }

    #[tokio::test]
    async fn stdio_drains_output_before_child_reads_input_in_both_modes() {
        let config = stdio_deadline_config(
            "import sys,json,signal; signal.alarm(2); sys.stdout.write(json.dumps('x'*262144)); sys.stdout.flush(); sys.stderr.write('e'*262144); sys.stderr.flush(); sys.stdin.read()",
        );
        let payload = serde_json::json!({"input": "x".repeat(1_000_000)});
        let result = execute_stdio_mode(&config, &payload).await;
        assert!(result.success, "one-shot pipe deadlock: {:?}", result.error);
        assert_eq!(result.output.unwrap().as_str().unwrap().len(), 262144);

        let state = WarmAppState {
            config: Arc::new(config),
            invoke_lock: Arc::new(Mutex::new(())),
            metrics: Arc::new(WatchdogMetrics::new()),
            function_name: "pipes".into(),
        };
        let mut headers = HeaderMap::new();
        headers.insert("x-execution-id", "pipes".parse().unwrap());
        assert_eq!(warm_invoke(State(state), headers, Json(payload)).await.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn stdio_deadline_includes_readers_after_leader_exits_in_both_modes() {
        assert_stdio_deadline_in_both_modes(
            r#"import os,sys,subprocess
sys.stdin.read()
if os.environ.get("EXECUTION_ID") != "next":
    subprocess.Popen([sys.executable,"-c","import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(1)"])
print("null")
"#,
            serde_json::json!({"input":"small"}),
        ).await;
    }

    #[tokio::test]
    async fn stdio_timeout_kills_descendant_after_leader_exits() {
        let pid_file = std::env::temp_dir().join(format!("nanofaas-watchdog-descendant-{}.pid", std::process::id()));
        let mut config = stdio_deadline_config(r#"import sys,subprocess
sys.stdin.read()
child = subprocess.Popen([sys.executable,"-c","import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(2)"])
with open(sys.argv[1], "w") as f:
    f.write(str(child.pid))
print("null")
"#);
        config.command.push(pid_file.to_string_lossy().into_owned());
        let result = execute_stdio_mode(&config, &serde_json::Value::Null).await;
        let pid = fs::read_to_string(&pid_file).await.unwrap().parse::<i32>().unwrap();
        let _ = fs::remove_file(&pid_file).await;
        let mut running = true;
        for _ in 0..20 {
            let status = std::process::Command::new("ps")
                .args(["-o", "stat=", "-p", &pid.to_string()]).output().unwrap();
            let state = String::from_utf8_lossy(&status.stdout);
            if state.trim().is_empty() || state.trim().starts_with('Z') {
                running = false;
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        let _ = signal::kill(Pid::from_raw(pid), Signal::SIGKILL); // clean up even when assertion fails
        assert_eq!(result.error.unwrap().code, "TIMEOUT");
        assert!(!running, "timed-out descendant survived after its leader was reaped");
    }

    async fn spawn_stub_runtime(status: StatusCode, envelope: bool) -> String {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let app = Router::new().route(
            "/",
            axum::routing::post(move || async move {
                let mut response = axum::response::Response::builder()
                    .status(status)
                    .header("content-type", "application/json")
                    .header("Location", "/x");
                if envelope {
                    response = response
                        .header("X-NanoFaaS-Function-Status", "true")
                        .header("X-NanoFaaS-Encoding", "base64");
                }
                response
                    .body(axum::body::Body::from(r#"{"error":"not found"}"#))
                    .unwrap()
            }),
        );
        tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        format!("http://{address}")
    }

    async fn invoke_http_returning(url: &str) -> axum::response::Response {
        let mut config = stdio_config(serde_json::Value::Null);
        config.mode = ExecutionMode::Http;
        config.runtime_url = url.to_string();
        let state = WarmAppState {
            config: Arc::new(config),
            invoke_lock: Arc::new(Mutex::new(())),
            metrics: Arc::new(WatchdogMetrics::new()),
            function_name: "test".to_string(),
        };
        let mut headers = HeaderMap::new();
        headers.insert("x-execution-id", "exec-1".parse().unwrap());

        warm_invoke(
            State(state),
            headers,
            Json(serde_json::json!({"input": "payload"})),
        )
        .await
    }

    #[test]
    fn parse_command_preserves_quoted_arguments() {
        assert_eq!(
            parse_command("python3 -c 'print(\"hello world\")'").unwrap(),
            vec!["python3", "-c", "print(\"hello world\")"]
        );
    }

    #[test]
    fn parse_command_rejects_unclosed_quote() {
        assert!(parse_command("python3 -c '").is_err());
    }

    #[test]
    fn parse_u64_rejects_invalid_values() {
        assert!(parse_u64("TIMEOUT_MS", "soon").is_err());
    }

    #[test]
    fn execution_mode_rejects_unknown_value() {
        assert!(ExecutionMode::parse("OTHER").is_err());
    }

    #[test]
    fn combined_image_runs_as_a_warm_http_proxy() {
        let dockerfile = include_str!("../Dockerfile.combined");

        assert!(dockerfile.contains("ENV WARM=true"));
        assert!(dockerfile.contains("ENV EXECUTION_MODE=HTTP"));
        assert!(dockerfile.contains("ENV RUNTIME_URL=http://127.0.0.1:8081/invoke"));
    }

    #[tokio::test]
    async fn warm_invoke_stdio_envelope_applies_status_headers_and_markers() {
        let response = invoke_stdio_returning(serde_json::json!({
            "__nanofaas_envelope__": true,
            "output": {"error": "not found"},
            "statusCode": 404,
            "headers": {"Location": "/x", "X-Custom": "dropped"},
            "encoding": "base64"
        }))
        .await;

        assert_eq!(response.status(), StatusCode::NOT_FOUND);
        assert_eq!(
            response
                .headers()
                .get("X-NanoFaaS-Function-Status")
                .unwrap(),
            "true"
        );
        assert_eq!(response.headers().get("X-NanoFaaS-Encoding").unwrap(), "base64");
        assert_eq!(response.headers().get("Location").unwrap(), "/x");
        assert!(response.headers().get("X-Custom").is_none());
    }

    #[tokio::test]
    async fn warm_invoke_stdio_plain_output_behaves_exactly_as_before() {
        let response = invoke_stdio_returning(serde_json::json!({"roman": "XLII"})).await;

        assert_eq!(response.status(), StatusCode::OK);
        assert!(response
            .headers()
            .get("X-NanoFaaS-Function-Status")
            .is_none());
        assert!(response.headers().get("X-NanoFaaS-Encoding").is_none());
    }

    #[tokio::test]
    async fn warm_invoke_stdio_out_of_range_status_is_a_platform_error() {
        let response = invoke_stdio_returning(serde_json::json!({
            "__nanofaas_envelope__": true,
            "output": "ok",
            "statusCode": 999
        }))
        .await;

        assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
        assert!(response
            .headers()
            .get("X-NanoFaaS-Function-Status")
            .is_none());
    }

    #[tokio::test]
    async fn warm_invoke_http_forwards_the_fronted_runtimes_envelope() {
        let upstream = spawn_stub_runtime(StatusCode::NOT_FOUND, true).await;

        let response = invoke_http_returning(&upstream).await;

        assert_eq!(response.status(), StatusCode::NOT_FOUND);
        assert_eq!(
            response
                .headers()
                .get("X-NanoFaaS-Function-Status")
                .unwrap(),
            "true"
        );
        assert_eq!(response.headers().get("X-NanoFaaS-Encoding").unwrap(), "base64");
        assert_eq!(response.headers().get("Location").unwrap(), "/x");
    }

    #[tokio::test]
    async fn warm_invoke_http_plain_success_behaves_exactly_as_before() {
        let upstream = spawn_stub_runtime(StatusCode::OK, false).await;

        let response = invoke_http_returning(&upstream).await;

        assert_eq!(response.status(), StatusCode::OK);
        assert!(response
            .headers()
            .get("X-NanoFaaS-Function-Status")
            .is_none());
    }

    #[test]
    fn invocation_result_serializes_envelope_fields_as_camel_case() {
        let envelope = envelope::Envelope {
            output: serde_json::json!({"error": "not found"}),
            status_code: 404,
            headers: std::collections::BTreeMap::from([(
                "Location".to_string(),
                "/x".to_string(),
            )]),
            encoding: Some("base64".to_string()),
        };

        let body = serde_json::to_string(&InvocationResult::success_with_envelope(envelope)).unwrap();

        assert!(body.contains(r#""statusCode":404"#), "got: {body}");
        assert!(body.contains(r#""encoding":"base64""#), "got: {body}");
        assert!(body.contains(r#""Location":"/x""#), "got: {body}");
        assert!(!body.contains("status_code"), "wire keys are camelCase, got: {body}");
        assert!(body.contains(r#""success":true"#), "got: {body}");
    }

    #[test]
    fn invocation_result_plain_success_omits_envelope_fields() {
        let body = serde_json::to_string(&InvocationResult::success(serde_json::json!("ok"))).unwrap();

        for key in ["statusCode", "headers", "encoding"] {
            assert!(!body.contains(key), "a plain success must omit {key}, got: {body}");
        }
    }
}
