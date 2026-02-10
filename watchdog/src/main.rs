//! mcFaas Watchdog
//!
//! Generic process supervisor for function containers.
//! Supports multiple execution modes:
//! - HTTP: Function exposes HTTP server (Java Spring, Python FastAPI)
//! - STDIO: Function reads from stdin, writes to stdout (Python scripts, Node)
//! - FILE: Function reads /tmp/input.json, writes /tmp/output.json (Bash, legacy)

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
use std::path::Path;
use std::process::ExitCode;
use std::sync::Arc;
use std::time::Duration;
use tokio::fs;
use tokio::io::AsyncWriteExt;
use tokio::process::{Child, Command};
use tokio::sync::Mutex;
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
    fn from_str(s: &str) -> Self {
        match s.to_uppercase().as_str() {
            "HTTP" => Self::Http,
            "STDIO" => Self::Stdio,
            "FILE" => Self::File,
            _ => Self::Http, // default
        }
    }
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

        let timeout_ms: u64 = env::var("TIMEOUT_MS")
            .unwrap_or_else(|_| "30000".to_string())
            .parse()
            .map_err(|_| "TIMEOUT_MS must be a number")?;

        let trace_id = env::var("TRACE_ID").ok();

        let command = env::var("WATCHDOG_CMD")
            .unwrap_or_else(|_| "java -jar /app/app.jar".to_string())
            .split_whitespace()
            .map(String::from)
            .collect();

        let mode = ExecutionMode::from_str(
            &env::var("EXECUTION_MODE").unwrap_or_else(|_| "HTTP".to_string())
        );

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

        let ready_timeout_ms: u64 = env::var("READY_TIMEOUT_MS")
            .unwrap_or_else(|_| "10000".to_string())
            .parse()
            .unwrap_or(10000);

        let input_file = env::var("INPUT_FILE")
            .unwrap_or_else(|_| "/tmp/input.json".to_string());

        let output_file = env::var("OUTPUT_FILE")
            .unwrap_or_else(|_| "/tmp/output.json".to_string());

        let warm_port: u16 = env::var("WARM_PORT")
            .unwrap_or_else(|_| "8080".to_string())
            .parse()
            .unwrap_or(8080);

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
struct InvocationResult {
    success: bool,
    output: Option<serde_json::Value>,
    error: Option<ErrorInfo>,
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

fn kill_process(child: &Child) {
    if let Some(pid) = child.id() {
        let pid = Pid::from_raw(pid as i32);
        info!(pid = pid.as_raw(), "Terminating process");
        // Try SIGTERM first
        let _ = signal::kill(pid, Signal::SIGTERM);
        // Give it 100ms to terminate gracefully
        std::thread::sleep(Duration::from_millis(100));
        // Force kill if still running
        let _ = signal::kill(pid, Signal::SIGKILL);
    }
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
    cmd.env("PORT", if config.warm { "8081" } else { "8080" });

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
    if config.command.is_empty() {
        return Err("No command specified".to_string());
    }

    let (program, args) = config.command.split_first().unwrap();
    info!(command = %program, mode = "STDIO", "Running function");

    let mut child = Command::new(program)
        .args(args)
        .env("EXECUTION_ID", execution_id)
        .env("TRACE_ID", trace_id.unwrap_or(""))
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .spawn()
        .map_err(|e| format!("Failed to spawn process: {}", e))?;

    // Write payload to stdin
    let payload_str = serde_json::to_string(payload)
        .map_err(|e| format!("Failed to serialize payload: {}", e))?;

    if let Some(mut stdin) = child.stdin.take() {
        stdin.write_all(payload_str.as_bytes()).await
            .map_err(|e| format!("Failed to write to stdin: {}", e))?;
        // Close stdin to signal EOF
        drop(stdin);
    }

    // Wait for process with timeout
    let output = timeout(
        Duration::from_millis(config.timeout_ms),
        child.wait_with_output()
    ).await
        .map_err(|_| "Process timed out".to_string())?
        .map_err(|e| format!("Process error: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("Process exited with {}: {}", output.status, stderr));
    }

    // Parse stdout as JSON
    let stdout = String::from_utf8_lossy(&output.stdout);
    serde_json::from_str(&stdout)
        .map_err(|e| format!("Invalid JSON output: {} (raw: {})", e, stdout.trim()))
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

    let mut child = Command::new(program)
        .args(args)
        .env("EXECUTION_ID", execution_id)
        .env("TRACE_ID", trace_id.unwrap_or(""))
        .env("INPUT_FILE", &config.input_file)
        .env("OUTPUT_FILE", &config.output_file)
        .spawn()
        .map_err(|e| format!("Failed to spawn process: {}", e))?;

    // Wait for process with timeout
    let status = timeout(
        Duration::from_millis(config.timeout_ms),
        child.wait()
    ).await
        .map_err(|_| {
            kill_process(&child);
            "Process timed out".to_string()
        })?
        .map_err(|e| format!("Process error: {}", e))?;

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

async fn warm_invoke(
    State(state): State<WarmAppState>,
    headers: HeaderMap,
    Json(payload): Json<serde_json::Value>,
) -> (StatusCode, Json<serde_json::Value>) {
    let execution_id = headers
        .get("x-execution-id")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");

    if execution_id.is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"error": "X-Execution-Id header is required"})),
        );
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
        ExecutionMode::Stdio => run_stdio_warm(&state.config, &payload, execution_id, trace_id).await,
        ExecutionMode::File => run_file_warm(&state.config, &payload, execution_id, trace_id).await,
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

    match out {
        Ok(v) => {
            state
                .metrics
                .invocations_total
                .get_or_create(&InvocationsLabels {
                    function: state.function_name.clone(),
                    mode: mode_str.to_string(),
                    success: "true".to_string(),
                })
                .inc();
            (StatusCode::OK, Json(v))
        }
        Err(e) => {
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
            (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({"error": e})))
        }
    }
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
) -> Result<serde_json::Value, String> {
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
            kill_process(&c);
            let _ = c.wait().await;
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
                kill_process(c);
                let _ = c.wait().await;
            }
            return ExitCode::from(1);
        }
    };

    info!(addr = %addr, "Warm server listening");

    let shutdown = async {
        tokio::signal::ctrl_c().await.ok();
        info!("Shutdown signal received");
    };

    if let Err(e) = axum::serve(listener, app)
        .with_graceful_shutdown(shutdown)
        .await
    {
        error!(error = %e, "Server error");
    }

    if let Some(ref mut c) = child {
        info!("Shutting down runtime");
        kill_process(c);
        let _ = c.wait().await;
    }

    info!("Warm server shutdown complete");
    ExitCode::SUCCESS
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
        kill_process(&child);
        let _ = child.wait().await;
        return InvocationResult::error("STARTUP_ERROR", &e);
    }

    // Invoke with timeout
    let invoke_result = timeout(
        Duration::from_millis(config.timeout_ms),
        invoke_http(config, payload)
    ).await;

    // Cleanup
    kill_process(&child);
    let _ = child.wait().await;

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
        Ok(output) => {
            info!("Function executed successfully");
            InvocationResult::success(output)
        }
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
        Ok(output) => {
            info!("Function executed successfully");
            InvocationResult::success(output)
        }
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
