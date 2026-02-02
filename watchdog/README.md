# nanofaas Watchdog

Lightweight Rust process supervisor for function containers. Supports multiple programming languages and execution modes.

## Supported Languages

| Language | Mode | Example |
|----------|------|---------|
| Java (Spring Boot) | HTTP | Server on port 8080 |
| Python (FastAPI/Flask) | HTTP | Server on port 8080 |
| Python (script) | STDIO | Read stdin, write stdout |
| Node.js (Express) | HTTP | Server on port 8080 |
| Node.js (script) | STDIO | Read stdin, write stdout |
| Bash/Shell | FILE | Read /tmp/input.json, write /tmp/output.json |
| Any binary | FILE or STDIO | Flexible |

## Execution Modes

### HTTP Mode (default)
For runtimes that expose an HTTP server.

```
Watchdog                          Function Runtime
    │                                    │
    ├── spawn process ──────────────────►│
    │                                    │
    ├── poll /health (max 10s) ────────►│
    │                              200 OK│
    │                                    │
    ├── POST /invoke {payload} ────────►│
    │                         {response}│
    │                                    │
    └── kill process ──────────────────►│
```

**Environment:**
```bash
EXECUTION_MODE=HTTP
WATCHDOG_CMD="java -jar /app/app.jar"
RUNTIME_URL=http://127.0.0.1:8080/invoke
HEALTH_URL=http://127.0.0.1:8080/health  # optional
```

### STDIO Mode
For simple scripts that read JSON from stdin and write JSON to stdout.

```
Watchdog                          Function Script
    │                                    │
    ├── spawn process ──────────────────►│
    │                                    │
    ├── write payload to stdin ─────────►│
    │   {"input": ...}                   │
    │                                    │
    │◄── read response from stdout ──────┤
    │   {"result": ...}                  │
    │                                    │
    └── (process exits)                  │
```

**Environment:**
```bash
EXECUTION_MODE=STDIO
WATCHDOG_CMD="python3 /app/handler.py"
```

### FILE Mode
For processes that prefer file-based I/O.

```
Watchdog                          Function Process
    │                                    │
    ├── write /tmp/input.json            │
    │                                    │
    ├── spawn process ──────────────────►│
    │                                    │
    │   (process reads INPUT_FILE)       │
    │   (process writes OUTPUT_FILE)     │
    │                                    │
    │◄── (process exits) ────────────────┤
    │                                    │
    └── read /tmp/output.json            │
```

**Environment:**
```bash
EXECUTION_MODE=FILE
WATCHDOG_CMD="/app/process.sh"
INPUT_FILE=/tmp/input.json
OUTPUT_FILE=/tmp/output.json
```

### WARM Mode (OpenWhisk-style)
For long-running runtimes that handle multiple sequential invocations. The container stays alive and processes requests one at a time.

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
HEALTH_URL=http://127.0.0.1:8080/health
WARM_PORT=8080
WARM_IDLE_TIMEOUT_MS=300000  # 5 min idle timeout (not yet implemented)
WARM_MAX_INVOCATIONS=0       # unlimited (not yet implemented)
```

**Watchdog API (warm mode):**

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Health check, returns 200 OK |
| `/invoke` | POST | Invoke function with JSON payload |

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

**Key Differences from HTTP Mode:**
- Watchdog exposes HTTP server instead of consuming ENV payload
- Runtime stays alive between invocations (warm start)
- Each invocation gets its own `execution_id` via request body
- Suitable for latency-sensitive workloads

## Examples

### Java (Spring Boot)

```dockerfile
FROM nanofaas/watchdog:latest AS watchdog
FROM eclipse-temurin:17-jre-alpine

COPY --from=watchdog /watchdog /usr/local/bin/watchdog
COPY app.jar /app/app.jar

ENV EXECUTION_MODE=HTTP
ENV WATCHDOG_CMD="java -jar /app/app.jar"
ENV RUNTIME_URL=http://127.0.0.1:8080/invoke

ENTRYPOINT ["/usr/local/bin/watchdog"]
```

### Python (FastAPI)

```dockerfile
FROM nanofaas/watchdog:latest AS watchdog
FROM python:3.11-slim

COPY --from=watchdog /watchdog /usr/local/bin/watchdog
COPY requirements.txt .
RUN pip install -r requirements.txt
COPY app.py /app/app.py

ENV EXECUTION_MODE=HTTP
ENV WATCHDOG_CMD="python3 -m uvicorn app:app --host 0.0.0.0 --port 8080"
ENV RUNTIME_URL=http://127.0.0.1:8080/invoke

ENTRYPOINT ["/usr/local/bin/watchdog"]
```

**app.py:**
```python
from fastapi import FastAPI

app = FastAPI()

@app.get("/health")
def health():
    return {"status": "ok"}

@app.post("/invoke")
def invoke(payload: dict):
    # Your function logic here
    return {"result": payload.get("input", "").upper()}
```

### Python (STDIO script)

```dockerfile
FROM nanofaas/watchdog:latest AS watchdog
FROM python:3.11-slim

COPY --from=watchdog /watchdog /usr/local/bin/watchdog
COPY handler.py /app/handler.py

ENV EXECUTION_MODE=STDIO
ENV WATCHDOG_CMD="python3 /app/handler.py"

ENTRYPOINT ["/usr/local/bin/watchdog"]
```

**handler.py:**
```python
#!/usr/bin/env python3
import json
import sys

# Read input from stdin
payload = json.load(sys.stdin)

# Your function logic
result = {"output": payload.get("input", "").upper()}

# Write output to stdout
json.dump(result, sys.stdout)
```

### Bash Script (FILE mode)

```dockerfile
FROM nanofaas/watchdog:latest AS watchdog
FROM alpine:3.19

COPY --from=watchdog /watchdog /usr/local/bin/watchdog
RUN apk add --no-cache jq bash
COPY handler.sh /app/handler.sh
RUN chmod +x /app/handler.sh

ENV EXECUTION_MODE=FILE
ENV WATCHDOG_CMD="/app/handler.sh"

ENTRYPOINT ["/usr/local/bin/watchdog"]
```

**handler.sh:**
```bash
#!/bin/bash

# Read input
INPUT=$(cat "$INPUT_FILE")

# Process (example: uppercase the input field)
RESULT=$(echo "$INPUT" | jq '{output: .input | ascii_upcase}')

# Write output
echo "$RESULT" > "$OUTPUT_FILE"
```

### Node.js (STDIO)

```dockerfile
FROM nanofaas/watchdog:latest AS watchdog
FROM node:20-alpine

COPY --from=watchdog /watchdog /usr/local/bin/watchdog
COPY handler.js /app/handler.js

ENV EXECUTION_MODE=STDIO
ENV WATCHDOG_CMD="node /app/handler.js"

ENTRYPOINT ["/usr/local/bin/watchdog"]
```

**handler.js:**
```javascript
let data = '';
process.stdin.on('data', chunk => data += chunk);
process.stdin.on('end', () => {
    const payload = JSON.parse(data);
    const result = { output: (payload.input || '').toUpperCase() };
    process.stdout.write(JSON.stringify(result));
});
```

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `EXECUTION_ID` | Yes | - | Unique execution identifier |
| `CALLBACK_URL` | Yes | - | Control plane callback URL |
| `EXECUTION_MODE` | No | HTTP | `HTTP`, `STDIO`, `FILE`, or `WARM` |
| `TIMEOUT_MS` | No | 30000 | Function timeout in milliseconds |
| `TRACE_ID` | No | - | Distributed tracing ID |
| `WATCHDOG_CMD` | No | `java -jar /app/app.jar` | Command to run |
| `RUNTIME_URL` | No | `http://127.0.0.1:8080/invoke` | HTTP invoke endpoint |
| `HEALTH_URL` | No | derived from RUNTIME_URL | HTTP health endpoint |
| `READY_TIMEOUT_MS` | No | 10000 | Max startup wait time |
| `INPUT_FILE` | No | `/tmp/input.json` | Input file (FILE mode) |
| `OUTPUT_FILE` | No | `/tmp/output.json` | Output file (FILE mode) |
| `INVOCATION_PAYLOAD` | No | `null` | JSON payload |
| `WARM_PORT` | No | 8080 | HTTP port for warm mode server |
| `WARM_IDLE_TIMEOUT_MS` | No | 300000 | Idle timeout before shutdown (not implemented) |
| `WARM_MAX_INVOCATIONS` | No | 0 | Max invocations before restart (not implemented) |

## Callback Format

### Success
```json
{
  "success": true,
  "output": { "result": "value" },
  "error": null
}
```

### Error
```json
{
  "success": false,
  "output": null,
  "error": {
    "code": "TIMEOUT",
    "message": "Function exceeded timeout of 30000ms"
  }
}
```

## Error Codes

| Code | Description |
|------|-------------|
| `SPAWN_ERROR` | Failed to start the function process |
| `STARTUP_ERROR` | Runtime didn't become ready (HTTP mode) |
| `FUNCTION_ERROR` | Function returned an error |
| `TIMEOUT` | Function exceeded configured timeout |

## Build

```bash
# Development
cargo build

# Release (optimized for size)
cargo build --release

# Docker
docker build -t nanofaas/watchdog:latest .
```

## Binary Size

| Build | Size |
|-------|------|
| Debug | ~15 MB |
| Release | ~4 MB |
| Release + strip | ~2 MB |

## Performance

| Metric | Value |
|--------|-------|
| Startup time | <1 ms |
| Memory overhead | ~2-3 MB |
| HTTP invoke latency | <1 ms |
| STDIO invoke latency | <1 ms |
