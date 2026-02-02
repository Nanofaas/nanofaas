//! mcFaas Watchdog
//!
//! Generic process supervisor for function containers.
//! Supports multiple execution modes:
//! - HTTP: Function exposes HTTP server (Java Spring, Python FastAPI)
//! - STDIO: Function reads from stdin, writes to stdout (Python scripts, Node)
//! - FILE: Function reads /tmp/input.json, writes /tmp/output.json (Bash, legacy)

use axum::{
    extract::State,
    http::StatusCode,
    routing::{get, post},
    Json, Router,
};
use nix::sys::signal::{self, Signal};
use nix::unistd::Pid;
use serde::{Deserialize, Serialize};
use std::env;
use std::path::Path;
use std::process::ExitCode;
use std::sync::Arc;
use std::time::Duration;
use tokio::fs;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::process::{Child, Command};
use tokio::sync::Mutex;
use tokio::time::{timeout, Instant};
use tracing::{debug, error, info, warn};

// ============================================================================
// Configuration
// ============================================================================

#[derive(Debug, Clone, Copy, PartialEq)]
enum ExecutionMode {
    Http,  // POST to HTTP endpoint (one-shot)
    Stdio, // stdin/stdout (one-shot)
    File,  // /tmp/input.json -> /tmp/output.json (one-shot)
    Warm,  // HTTP server receiving multiple invocations (persistent)
}

impl ExecutionMode {
    fn from_str(s: &str) -> Self {
        match s.to_uppercase().as_str() {
            "HTTP" => Self::Http,
            "STDIO" => Self::Stdio,
            "FILE" => Self::File,
            "WARM" => Self::Warm,
            _ => Self::Http, // default
        }
    }
}

#[derive(Debug, Clone)]
struct Config {
    /// URL for callback to control plane
    callback_url: String,
    /// Execution ID
    execution_id: String,
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
    /// Port for warm mode HTTP server
    warm_port: u16,
    /// Idle timeout before shutdown (ms) - 0 means no timeout
    warm_idle_timeout_ms: u64,
    /// Max invocations before restart - 0 means unlimited
    warm_max_invocations: u64,
}

impl Config {
    fn from_env() -> Result<Self, String> {
        let callback_url = env::var("CALLBACK_URL")
            .map_err(|_| "CALLBACK_URL not set")?;

        let execution_id = env::var("EXECUTION_ID")
            .map_err(|_| "EXECUTION_ID not set")?;

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

        let runtime_url = env::var("RUNTIME_URL")
            .unwrap_or_else(|_| "http://127.0.0.1:8080/invoke".to_string());

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

        let warm_idle_timeout_ms: u64 = env::var("WARM_IDLE_TIMEOUT_MS")
            .unwrap_or_else(|_| "300000".to_string()) // 5 minutes default
            .parse()
            .unwrap_or(300000);

        let warm_max_invocations: u64 = env::var("WARM_MAX_INVOCATIONS")
            .unwrap_or_else(|_| "0".to_string())
            .parse()
            .unwrap_or(0);

        Ok(Config {
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
            warm_idle_timeout_ms,
            warm_max_invocations,
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
// Warm Mode State
// ============================================================================

struct WarmState {
    config: Config,
    invocation_count: u64,
    last_invocation: Instant,
}

impl WarmState {
    fn new(config: Config) -> Self {
        Self {
            config,
            invocation_count: 0,
            last_invocation: Instant::now(),
        }
    }
}

#[derive(Deserialize)]
struct WarmInvokeRequest {
    execution_id: String,
    callback_url: String,
    #[serde(default)]
    trace_id: Option<String>,
    payload: serde_json::Value,
    #[serde(default = "default_timeout")]
    timeout_ms: u64,
}

fn default_timeout() -> u64 {
    30000
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

    Command::new(program)
        .args(args)
        .env("EXECUTION_ID", &config.execution_id)
        .env("PORT", "8080")
        .spawn()
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

async fn run_stdio(
    config: &Config,
    payload: &serde_json::Value,
) -> Result<serde_json::Value, String> {
    if config.command.is_empty() {
        return Err("No command specified".to_string());
    }

    let (program, args) = config.command.split_first().unwrap();
    info!(command = %program, mode = "STDIO", "Running function");

    let mut child = Command::new(program)
        .args(args)
        .env("EXECUTION_ID", &config.execution_id)
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

async fn run_file(
    config: &Config,
    payload: &serde_json::Value,
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
        .env("EXECUTION_ID", &config.execution_id)
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
    let client = reqwest::Client::new();

    let url = if config.callback_url.ends_with(":complete") {
        config.callback_url.clone()
    } else {
        format!("{}/{}:complete", config.callback_url, config.execution_id)
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
// Warm Mode
// ============================================================================

async fn warm_health() -> StatusCode {
    StatusCode::OK
}

async fn warm_invoke(
    State(state): State<Arc<Mutex<WarmState>>>,
    Json(req): Json<WarmInvokeRequest>,
) -> (StatusCode, Json<InvocationResult>) {
    let (config, invocation_count) = {
        let mut state_guard = state.lock().await;
        state_guard.invocation_count += 1;
        state_guard.last_invocation = Instant::now();
        (state_guard.config.clone(), state_guard.invocation_count)
    }; // Lock is released here

    info!(
        execution_id = %req.execution_id,
        invocation = invocation_count,
        "Processing warm invocation"
    );

    // Forward to runtime
    let client = reqwest::Client::new();
    let invoke_result = client
        .post(&config.runtime_url)
        .header("X-Execution-Id", &req.execution_id)
        .header("X-Trace-Id", req.trace_id.as_deref().unwrap_or(""))
        .json(&req.payload)
        .timeout(Duration::from_millis(req.timeout_ms))
        .send()
        .await;

    let result = match invoke_result {
        Ok(response) if response.status().is_success() => {
            match response.json::<serde_json::Value>().await {
                Ok(output) => InvocationResult::success(output),
                Err(e) => InvocationResult::error("PARSE_ERROR", &e.to_string()),
            }
        }
        Ok(response) => {
            let status = response.status();
            let body = response.text().await.unwrap_or_default();
            InvocationResult::error("RUNTIME_ERROR", &format!("{}: {}", status, body))
        }
        Err(e) if e.is_timeout() => {
            InvocationResult::error("TIMEOUT", &format!("Timeout after {}ms", req.timeout_ms))
        }
        Err(e) => InvocationResult::error("INVOKE_ERROR", &e.to_string()),
    };

    // Send callback (best effort)
    let callback_url = format!("{}/{}:complete", req.callback_url, req.execution_id);
    let _ = client
        .post(&callback_url)
        .header("X-Trace-Id", req.trace_id.as_deref().unwrap_or(""))
        .json(&result)
        .timeout(Duration::from_secs(10))
        .send()
        .await;

    let status = if result.success {
        StatusCode::OK
    } else {
        StatusCode::INTERNAL_SERVER_ERROR
    };

    (status, Json(result))
}

async fn execute_warm_mode(config: Config) -> ExitCode {
    info!(port = config.warm_port, "Starting warm mode");

    // Spawn runtime
    let mut child = match spawn_http_runtime(&config).await {
        Ok(c) => c,
        Err(e) => {
            error!(error = %e, "Failed to spawn runtime");
            return ExitCode::from(1);
        }
    };

    // Wait for runtime ready
    if let Err(e) = wait_for_http_ready(&config).await {
        error!(error = %e, "Runtime failed to start");
        kill_process(&child);
        let _ = child.wait().await;
        return ExitCode::from(1);
    }

    info!("Runtime ready, starting warm HTTP server");

    let state = Arc::new(Mutex::new(WarmState::new(config.clone())));

    let app = Router::new()
        .route("/health", get(warm_health))
        .route("/invoke", post(warm_invoke))
        .with_state(state);

    let addr = std::net::SocketAddr::from(([0, 0, 0, 0], config.warm_port));
    let listener = match tokio::net::TcpListener::bind(addr).await {
        Ok(l) => l,
        Err(e) => {
            error!(error = %e, "Failed to bind to port");
            kill_process(&child);
            let _ = child.wait().await;
            return ExitCode::from(1);
        }
    };

    info!(addr = %addr, "Warm mode server listening");

    // Handle shutdown signal
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

    // Cleanup
    info!("Shutting down runtime");
    kill_process(&child);
    let _ = child.wait().await;

    info!("Warm mode shutdown complete");
    ExitCode::SUCCESS
}

// ============================================================================
// Main
// ============================================================================

#[tokio::main(flavor = "current_thread")]
async fn main() -> ExitCode {
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
        execution_id = %config.execution_id,
        timeout_ms = config.timeout_ms,
        mode = ?config.mode,
        "Configuration loaded"
    );

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
        ExecutionMode::Warm => {
            return execute_warm_mode(config).await;
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
    match run_stdio(config, payload).await {
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
    match run_file(config, payload).await {
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
