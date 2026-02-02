# OpenWhisk Warm Mode Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement OpenWhisk-style warm container mode where containers stay alive and handle multiple sequential invocations, reducing cold start latency.

**Architecture:** Add WARM execution mode to watchdog that exposes an HTTP endpoint for receiving invocations. The watchdog spawns the runtime once, keeps it alive, and forwards invocations sequentially. The control-plane's existing PoolDispatcher routes to warm containers. Function runtimes accept execution ID per-request via header instead of environment variable.

**Tech Stack:** Rust (watchdog), Java 21/Spring Boot (function-runtime), Python 3.11/Flask (python-runtime), JUnit 5, pytest

**Skills:** @superpowers:test-driven-development, @superpowers:verification-before-completion

---

## Phase 1: Watchdog Warm Mode

### Task 1: Add axum HTTP server dependency to watchdog

**Files:**
- Modify: `watchdog/Cargo.toml`

**Step 1: Add axum dependency**

Edit `watchdog/Cargo.toml` to add the HTTP server:

```toml
[dependencies]
tokio = { version = "1", features = ["rt", "process", "time", "signal", "macros", "fs", "io-util", "sync"] }
reqwest = { version = "0.11", default-features = false, features = ["rustls-tls", "json"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter", "json"] }
nix = { version = "0.27", features = ["signal", "process"] }
axum = "0.7"
```

**Step 2: Verify build**

Run: `cd watchdog && cargo build`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add watchdog/Cargo.toml
git commit -m "feat(watchdog): add axum dependency for warm mode HTTP server"
```

---

### Task 2: Add WARM execution mode enum

**Files:**
- Modify: `watchdog/src/main.rs:26-42`

**Step 1: Update ExecutionMode enum**

```rust
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
```

**Step 2: Verify build**

Run: `cd watchdog && cargo build`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add watchdog/src/main.rs
git commit -m "feat(watchdog): add WARM execution mode enum"
```

---

### Task 3: Add warm mode configuration

**Files:**
- Modify: `watchdog/src/main.rs:44-125` (Config struct)

**Step 1: Add warm mode fields to Config**

Add these fields to the Config struct:

```rust
#[derive(Debug)]
struct Config {
    // ... existing fields ...

    /// Port for warm mode HTTP server
    warm_port: u16,
    /// Idle timeout before shutdown (ms) - 0 means no timeout
    warm_idle_timeout_ms: u64,
    /// Max invocations before restart - 0 means unlimited
    warm_max_invocations: u64,
}
```

**Step 2: Add parsing in Config::from_env()**

Add after existing parsing:

```rust
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
```

Update the Config return to include new fields.

**Step 3: Verify build**

Run: `cd watchdog && cargo build`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add watchdog/src/main.rs
git commit -m "feat(watchdog): add warm mode configuration fields"
```

---

### Task 4: Implement warm mode HTTP server

**Files:**
- Modify: `watchdog/src/main.rs` (add new module/functions)

**Step 1: Add imports at top of file**

```rust
use axum::{
    extract::State,
    http::{HeaderMap, StatusCode},
    routing::{get, post},
    Json, Router,
};
use std::sync::Arc;
use tokio::sync::Mutex;
```

**Step 2: Add WarmState struct**

```rust
struct WarmState {
    config: Config,
    runtime_child: Option<Child>,
    invocation_count: u64,
    last_invocation: Instant,
}

impl WarmState {
    fn new(config: Config) -> Self {
        Self {
            config,
            runtime_child: None,
            invocation_count: 0,
            last_invocation: Instant::now(),
        }
    }
}
```

**Step 3: Add health endpoint handler**

```rust
async fn warm_health() -> StatusCode {
    StatusCode::OK
}
```

**Step 4: Add invoke endpoint handler**

```rust
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

async fn warm_invoke(
    State(state): State<Arc<Mutex<WarmState>>>,
    headers: HeaderMap,
    Json(req): Json<WarmInvokeRequest>,
) -> (StatusCode, Json<InvocationResult>) {
    let mut state = state.lock().await;
    state.invocation_count += 1;
    state.last_invocation = Instant::now();

    // Forward to runtime
    let result = invoke_http_with_config(
        &state.config,
        &req.payload,
        &req.execution_id,
        req.trace_id.as_deref(),
    ).await;

    // Send callback (best effort)
    let callback_config = Config {
        callback_url: req.callback_url.clone(),
        execution_id: req.execution_id.clone(),
        trace_id: req.trace_id,
        ..state.config.clone()
    };
    let _ = send_callback(&callback_config, result.clone()).await;

    match &result {
        r if r.success => (StatusCode::OK, Json(result)),
        _ => (StatusCode::INTERNAL_SERVER_ERROR, Json(result)),
    }
}
```

**Step 5: Add main warm mode executor**

```rust
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
        .with_state(state.clone());

    let addr = std::net::SocketAddr::from(([0, 0, 0, 0], config.warm_port));
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();

    info!(addr = %addr, "Warm mode server listening");

    // Handle shutdown
    let shutdown = async {
        tokio::signal::ctrl_c().await.ok();
        info!("Shutdown signal received");
    };

    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown)
        .await
        .unwrap();

    // Cleanup
    kill_process(&child);
    let _ = child.wait().await;

    info!("Warm mode shutdown complete");
    ExitCode::SUCCESS
}
```

**Step 6: Update main() to handle WARM mode**

In the main match statement, add:

```rust
ExecutionMode::Warm => {
    return execute_warm_mode(config).await;
}
```

**Step 7: Verify build**

Run: `cd watchdog && cargo build`
Expected: BUILD SUCCESS

**Step 8: Commit**

```bash
git add watchdog/src/main.rs
git commit -m "feat(watchdog): implement warm mode HTTP server"
```

---

### Task 5: Add warm mode integration test

**Files:**
- Create: `watchdog/tests/integration/test_warm_mode.sh`

**Step 1: Write the test script**

```bash
#!/bin/bash
set -euo pipefail

source "$(dirname "$0")/test_helpers.sh"

TEST_NAME="warm_mode"

setup_test() {
    # Start a simple HTTP echo server as the "runtime"
    python3 "$(dirname "$0")/../fixtures/http_server.py" &
    RUNTIME_PID=$!
    sleep 1
}

teardown_test() {
    kill $RUNTIME_PID 2>/dev/null || true
    kill $WATCHDOG_PID 2>/dev/null || true
}

run_test() {
    # Start watchdog in warm mode
    EXECUTION_MODE=WARM \
    CALLBACK_URL=http://localhost:9999 \
    EXECUTION_ID=ignored-in-warm-mode \
    WARM_PORT=8081 \
    WATCHDOG_CMD="sleep infinity" \
    RUNTIME_URL=http://127.0.0.1:8080/invoke \
    ../target/debug/nanofaas-watchdog &
    WATCHDOG_PID=$!

    sleep 2

    # Health check
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/health)
    assert_equals "200" "$HTTP_CODE" "Health check should return 200"

    # First invocation
    RESPONSE=$(curl -s -X POST http://localhost:8081/invoke \
        -H "Content-Type: application/json" \
        -d '{
            "execution_id": "exec-1",
            "callback_url": "http://localhost:9999",
            "payload": {"input": "hello"}
        }')

    assert_contains "$RESPONSE" "success" "First invocation should succeed"

    # Second invocation (same container!)
    RESPONSE=$(curl -s -X POST http://localhost:8081/invoke \
        -H "Content-Type: application/json" \
        -d '{
            "execution_id": "exec-2",
            "callback_url": "http://localhost:9999",
            "payload": {"input": "world"}
        }')

    assert_contains "$RESPONSE" "success" "Second invocation should succeed"

    echo "✓ Warm mode test passed"
}

trap teardown_test EXIT
setup_test
run_test
```

**Step 2: Make executable**

Run: `chmod +x watchdog/tests/integration/test_warm_mode.sh`

**Step 3: Verify test runs**

Run: `cd watchdog && cargo build && ./tests/integration/test_warm_mode.sh`
Expected: "✓ Warm mode test passed"

**Step 4: Commit**

```bash
git add watchdog/tests/integration/test_warm_mode.sh
git commit -m "test(watchdog): add warm mode integration test"
```

---

## Phase 2: Java Function Runtime Updates

### Task 6: Accept execution ID from header

**Files:**
- Modify: `function-runtime/src/main/java/it/unimib/datai/nanofaas/runtime/api/InvokeController.java`
- Test: `function-runtime/src/test/java/it/unimib/datai/nanofaas/runtime/InvokeControllerTest.java`

**Step 1: Write the failing test**

Add to `InvokeControllerTest.java`:

```java
@Test
void invokeUsesExecutionIdFromHeader() throws Exception {
    mockMvc.perform(post("/invoke")
            .header("X-Execution-Id", "header-exec-123")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"input\": \"test\", \"metadata\": {}}"))
            .andExpect(status().isOk());

    // Verify callback was called with header execution ID
    verify(callbackClient).sendResult(eq("header-exec-123"), any(InvocationResult.class));
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :function-runtime:test --tests "*.InvokeControllerTest.invokeUsesExecutionIdFromHeader"`
Expected: FAIL (uses ENV execution ID, not header)

**Step 3: Update InvokeController**

```java
@PostMapping("/invoke")
public ResponseEntity<Object> invoke(
        @RequestBody InvocationRequest request,
        @RequestHeader(value = "X-Execution-Id", required = false) String headerExecutionId) {

    // Prefer header over ENV (for warm mode)
    String effectiveExecutionId = (headerExecutionId != null && !headerExecutionId.isBlank())
            ? headerExecutionId
            : this.executionId;

    if (effectiveExecutionId == null || effectiveExecutionId.isBlank()) {
        log.error("No execution ID provided (header or ENV)");
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Execution ID not configured"));
    }

    try {
        FunctionHandler handler = handlerRegistry.resolve();
        Object output = handler.handle(request);

        boolean callbackSent = callbackClient.sendResult(effectiveExecutionId, InvocationResult.success(output));
        if (!callbackSent) {
            log.warn("Callback failed for execution {} but function succeeded", effectiveExecutionId);
        }

        return ResponseEntity.ok(output);
    } catch (Exception ex) {
        log.error("Handler error for execution {}: {}", effectiveExecutionId, ex.getMessage(), ex);
        callbackClient.sendResult(effectiveExecutionId, InvocationResult.error("HANDLER_ERROR", ex.getMessage()));
        return ResponseEntity.status(500)
                .body(Map.of("error", ex.getMessage()));
    }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :function-runtime:test --tests "*.InvokeControllerTest.invokeUsesExecutionIdFromHeader"`
Expected: PASS

**Step 5: Commit**

```bash
git add function-runtime/src/main/java/it/unimib/datai/nanofaas/runtime/api/InvokeController.java \
  function-runtime/src/test/java/it/unimib/datai/nanofaas/runtime/InvokeControllerTest.java
git commit -m "feat(runtime): accept X-Execution-Id header for warm mode"
```

---

### Task 7: Propagate trace ID from header

**Files:**
- Modify: `function-runtime/src/main/java/it/unimib/datai/nanofaas/runtime/api/InvokeController.java`
- Modify: `function-runtime/src/main/java/it/unimib/datai/nanofaas/runtime/core/CallbackClient.java`

**Step 1: Write the failing test**

```java
@Test
void invokePropagatestTraceIdToCallback() throws Exception {
    mockMvc.perform(post("/invoke")
            .header("X-Execution-Id", "exec-123")
            .header("X-Trace-Id", "trace-456")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"input\": \"test\", \"metadata\": {}}"))
            .andExpect(status().isOk());

    verify(callbackClient).sendResult(eq("exec-123"), any(InvocationResult.class), eq("trace-456"));
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :function-runtime:test --tests "*.InvokeControllerTest.invokePropagatestTraceIdToCallback"`
Expected: FAIL (method signature doesn't match)

**Step 3: Update CallbackClient to accept traceId**

Add overloaded method:

```java
public boolean sendResult(String executionId, InvocationResult result, String traceId) {
    // ... existing logic with X-Trace-Id header if traceId != null
}
```

**Step 4: Update InvokeController to pass trace ID**

```java
@PostMapping("/invoke")
public ResponseEntity<Object> invoke(
        @RequestBody InvocationRequest request,
        @RequestHeader(value = "X-Execution-Id", required = false) String headerExecutionId,
        @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
    // ... use traceId in callback
    callbackClient.sendResult(effectiveExecutionId, InvocationResult.success(output), traceId);
}
```

**Step 5: Run test to verify it passes**

Run: `./gradlew :function-runtime:test --tests "*.InvokeControllerTest.invokePropagatestTraceIdToCallback"`
Expected: PASS

**Step 6: Commit**

```bash
git add function-runtime/src/main/java/it/unimib/datai/nanofaas/runtime/api/InvokeController.java \
  function-runtime/src/main/java/it/unimib/datai/nanofaas/runtime/core/CallbackClient.java \
  function-runtime/src/test/java/it/unimib/datai/nanofaas/runtime/InvokeControllerTest.java
git commit -m "feat(runtime): propagate X-Trace-Id header to callback"
```

---

## Phase 3: Python Runtime

### Task 8: Create python-runtime module structure

**Files:**
- Create: `python-runtime/requirements.txt`
- Create: `python-runtime/src/nanofaas_runtime/__init__.py`
- Create: `python-runtime/src/nanofaas_runtime/app.py`
- Create: `python-runtime/Dockerfile`

**Step 1: Create requirements.txt**

```
flask==3.0.0
requests==2.31.0
gunicorn==21.2.0
```

**Step 2: Create app.py**

```python
"""nanofaas Python Function Runtime"""
import importlib
import logging
import os
import sys

from flask import Flask, request, jsonify
import requests

logging.basicConfig(
    level=logging.INFO,
    format='{"timestamp":"%(asctime)s","level":"%(levelname)s","message":"%(message)s"}'
)
logger = logging.getLogger(__name__)

app = Flask(__name__)

# Configuration
CALLBACK_URL = os.environ.get('CALLBACK_URL', '')
DEFAULT_EXECUTION_ID = os.environ.get('EXECUTION_ID', '')
HANDLER_MODULE = os.environ.get('HANDLER_MODULE', 'handler')
HANDLER_FUNCTION = os.environ.get('HANDLER_FUNCTION', 'handle')

# Load handler
_handler = None

def get_handler():
    global _handler
    if _handler is None:
        try:
            module = importlib.import_module(HANDLER_MODULE)
            _handler = getattr(module, HANDLER_FUNCTION)
            logger.info(f"Loaded handler: {HANDLER_MODULE}:{HANDLER_FUNCTION}")
        except Exception as e:
            logger.error(f"Failed to load handler: {e}")
            raise
    return _handler


@app.route('/health', methods=['GET'])
def health():
    return jsonify({"status": "ok"})


@app.route('/invoke', methods=['POST'])
def invoke():
    # Get execution ID from header (warm mode) or ENV (one-shot)
    execution_id = request.headers.get('X-Execution-Id', DEFAULT_EXECUTION_ID)
    trace_id = request.headers.get('X-Trace-Id')
    callback_url = request.headers.get('X-Callback-Url', CALLBACK_URL)

    if not execution_id:
        return jsonify({"error": "No execution ID provided"}), 400

    try:
        payload = request.get_json()
        handler = get_handler()
        result = handler(payload)

        # Send callback (best effort)
        if callback_url:
            _send_callback(callback_url, execution_id, trace_id, {
                "success": True,
                "output": result,
                "error": None
            })

        return jsonify(result)

    except Exception as e:
        logger.exception(f"Handler error: {e}")

        if callback_url:
            _send_callback(callback_url, execution_id, trace_id, {
                "success": False,
                "output": None,
                "error": {"code": "HANDLER_ERROR", "message": str(e)}
            })

        return jsonify({"error": str(e)}), 500


def _send_callback(callback_url: str, execution_id: str, trace_id: str, result: dict):
    try:
        url = f"{callback_url}/{execution_id}:complete"
        headers = {"Content-Type": "application/json"}
        if trace_id:
            headers["X-Trace-Id"] = trace_id

        resp = requests.post(url, json=result, headers=headers, timeout=10)
        if not resp.ok:
            logger.warning(f"Callback failed: {resp.status_code}")
    except Exception as e:
        logger.warning(f"Callback error: {e}")


if __name__ == '__main__':
    port = int(os.environ.get('PORT', 8080))
    app.run(host='0.0.0.0', port=port)
```

**Step 3: Create Dockerfile**

```dockerfile
FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY src/ .

ENV PYTHONPATH=/app
ENV PORT=8080

EXPOSE 8080

CMD ["gunicorn", "-b", "0.0.0.0:8080", "-w", "1", "nanofaas_runtime.app:app"]
```

**Step 4: Create __init__.py**

```python
"""nanofaas Python Runtime"""
__version__ = "0.5.0"
```

**Step 5: Verify Docker build**

Run: `docker build -t nanofaas/python-runtime:test python-runtime/`
Expected: BUILD SUCCESS

**Step 6: Commit**

```bash
git add python-runtime/
git commit -m "feat: add python-runtime module"
```

---

### Task 9: Add Python runtime tests

**Files:**
- Create: `python-runtime/tests/test_app.py`
- Create: `python-runtime/tests/conftest.py`
- Create: `python-runtime/tests/fixtures/handler.py`

**Step 1: Create conftest.py**

```python
import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))

import pytest
from nanofaas_runtime.app import app

@pytest.fixture
def client():
    app.config['TESTING'] = True
    with app.test_client() as client:
        yield client
```

**Step 2: Create test handler fixture**

```python
# tests/fixtures/handler.py
def handle(request):
    return {"echo": request.get("input", "").upper()}
```

**Step 3: Create test_app.py**

```python
import os
import pytest

os.environ['HANDLER_MODULE'] = 'fixtures.handler'
os.environ['HANDLER_FUNCTION'] = 'handle'


def test_health(client):
    response = client.get('/health')
    assert response.status_code == 200
    assert response.json['status'] == 'ok'


def test_invoke_with_header_execution_id(client):
    response = client.post('/invoke',
        json={"input": "hello"},
        headers={"X-Execution-Id": "exec-123"})

    assert response.status_code == 200
    assert response.json['echo'] == 'HELLO'


def test_invoke_without_execution_id_fails(client):
    os.environ['EXECUTION_ID'] = ''
    response = client.post('/invoke',
        json={"input": "hello"})

    assert response.status_code == 400


def test_invoke_with_env_execution_id(client):
    os.environ['EXECUTION_ID'] = 'env-exec-456'
    response = client.post('/invoke',
        json={"input": "world"})

    assert response.status_code == 200
    assert response.json['echo'] == 'WORLD'
```

**Step 4: Add pytest to requirements**

Add to `python-runtime/requirements.txt`:
```
pytest==7.4.0
```

**Step 5: Run tests**

Run: `cd python-runtime && pip install -r requirements.txt && PYTHONPATH=src:tests pytest tests/ -v`
Expected: All tests PASS

**Step 6: Commit**

```bash
git add python-runtime/tests/ python-runtime/requirements.txt
git commit -m "test(python-runtime): add unit tests"
```

---

### Task 10: Add Python runtime to build system

**Files:**
- Create: `python-runtime/build.sh`
- Modify: `README.md`

**Step 1: Create build script**

```bash
#!/bin/bash
set -euo pipefail

VERSION=${VERSION:-0.5.0}
IMAGE=${IMAGE:-nanofaas/python-runtime:$VERSION}

docker build -t "$IMAGE" .

echo "Built: $IMAGE"
```

**Step 2: Make executable**

Run: `chmod +x python-runtime/build.sh`

**Step 3: Update README.md**

Add Python runtime to modules section:
```markdown
- `python-runtime/` HTTP runtime for Python function handlers
```

**Step 4: Commit**

```bash
git add python-runtime/build.sh README.md
git commit -m "feat(python-runtime): add build script and docs"
```

---

## Phase 4: Documentation

### Task 11: Update watchdog README with warm mode

**Files:**
- Modify: `watchdog/README.md`

**Step 1: Add WARM mode documentation**

Add new section after FILE mode:

```markdown
### WARM Mode (OpenWhisk-style)
For long-running runtimes that handle multiple sequential invocations.

```
Control Plane                    Watchdog                    Runtime
    │                               │                           │
    │── POST /invoke ──────────────►│                           │
    │   {execution_id, payload}     ├── POST /invoke ──────────►│
    │                               │◄─────────── {response} ───┤
    │◄───────── {response} ─────────┤                           │
    │                               │                           │
    │── POST /invoke ──────────────►│  (same container!)        │
    │   {execution_id, payload}     ├── POST /invoke ──────────►│
    │                               │◄─────────── {response} ───┤
    │◄───────── {response} ─────────┤                           │
```

**Environment:**
```bash
EXECUTION_MODE=WARM
WATCHDOG_CMD="java -jar /app/app.jar"
RUNTIME_URL=http://127.0.0.1:8080/invoke
WARM_PORT=8080
WARM_IDLE_TIMEOUT_MS=300000  # 5 min idle timeout
WARM_MAX_INVOCATIONS=0       # unlimited
```

**Invoke Request Format:**
```json
{
  "execution_id": "exec-123",
  "callback_url": "http://control-plane:8080/v1/internal/executions",
  "trace_id": "trace-456",
  "payload": {"input": "..."},
  "timeout_ms": 30000
}
```
```

**Step 2: Commit**

```bash
git add watchdog/README.md
git commit -m "docs(watchdog): add WARM mode documentation"
```

---

### Task 12: Add Python runtime README

**Files:**
- Create: `python-runtime/README.md`

**Step 1: Write README**

```markdown
# nanofaas Python Runtime

Lightweight Python function runtime for nanofaas. Supports both one-shot and warm (OpenWhisk-style) execution modes.

## Handler Interface

Create a handler module with a `handle` function:

```python
# handler.py
def handle(request: dict) -> dict:
    """
    Process the invocation request.

    Args:
        request: Dict with 'input' and optional 'metadata'

    Returns:
        Dict with function result
    """
    return {"result": request.get("input", "").upper()}
```

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `HANDLER_MODULE` | `handler` | Python module containing handler |
| `HANDLER_FUNCTION` | `handle` | Function name to call |
| `CALLBACK_URL` | - | Control plane callback URL |
| `EXECUTION_ID` | - | Default execution ID (ENV mode) |
| `PORT` | `8080` | HTTP server port |

## Docker Usage

```dockerfile
FROM nanofaas/python-runtime:0.5.0

COPY handler.py /app/handler.py

ENV HANDLER_MODULE=handler
ENV HANDLER_FUNCTION=handle
```

## Warm Mode

In warm mode, execution ID comes from `X-Execution-Id` header per request.

```bash
curl -X POST http://localhost:8080/invoke \
  -H "Content-Type: application/json" \
  -H "X-Execution-Id: exec-123" \
  -d '{"input": "hello"}'
```

## Development

```bash
# Install dependencies
pip install -r requirements.txt

# Run tests
PYTHONPATH=src:tests pytest tests/ -v

# Run locally
HANDLER_MODULE=tests.fixtures.handler python -m nanofaas_runtime.app
```
```

**Step 2: Commit**

```bash
git add python-runtime/README.md
git commit -m "docs(python-runtime): add README"
```

---

## Summary

| Phase | Tasks | Components |
|-------|-------|------------|
| 1 | 1-5 | Watchdog warm mode |
| 2 | 6-7 | Java runtime header support |
| 3 | 8-10 | Python runtime |
| 4 | 11-12 | Documentation |

**Total: 12 tasks**

After completion, warm mode can be tested end-to-end:
1. Deploy watchdog + runtime with `EXECUTION_MODE=WARM`
2. Send multiple invocations to watchdog's `/invoke` endpoint
3. Verify single runtime process handles all invocations
