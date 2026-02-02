# nanofaas Python Runtime

Lightweight Python function runtime for nanofaas. Supports both one-shot (cold) and warm (OpenWhisk-style) execution modes.

## Handler Interface

Create a handler module with a `handle` function:

```python
# handler.py
def handle(request: dict) -> dict:
    """
    Process the invocation request.

    Args:
        request: Dict containing 'input' and optional 'metadata'

    Returns:
        Dict with function result
    """
    input_value = request.get("input", "")
    return {"result": input_value.upper()}
```

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `HANDLER_MODULE` | `handler` | Python module containing handler function |
| `HANDLER_FUNCTION` | `handle` | Function name to call |
| `CALLBACK_URL` | - | Control plane callback URL (one-shot mode) |
| `EXECUTION_ID` | - | Execution ID (one-shot mode) |
| `PORT` | `8080` | HTTP server port |

## Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Health check, returns `{"status": "ok"}` |
| `/invoke` | POST | Invoke the function handler |

## Docker Usage

### Basic Dockerfile

```dockerfile
FROM nanofaas/python-runtime:0.5.0

COPY handler.py /app/handler.py

ENV HANDLER_MODULE=handler
ENV HANDLER_FUNCTION=handle
```

### With Dependencies

```dockerfile
FROM nanofaas/python-runtime:0.5.0

COPY requirements.txt /app/
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY handler.py /app/handler.py

ENV HANDLER_MODULE=handler
ENV HANDLER_FUNCTION=handle
```

## Execution Modes

### One-Shot Mode (Default)

Used with Kubernetes Jobs. Execution ID comes from environment variable.

```bash
docker run -e EXECUTION_ID=exec-123 \
           -e CALLBACK_URL=http://control-plane:8080/v1/internal/executions \
           -e HANDLER_MODULE=handler \
           -v $(pwd)/handler.py:/app/handler.py \
           nanofaas/python-runtime:0.5.0
```

### Warm Mode (OpenWhisk-style)

Used with persistent pods. Execution ID comes from request header.

```bash
# Invoke via watchdog or directly:
curl -X POST http://localhost:8080/invoke \
  -H "Content-Type: application/json" \
  -H "X-Execution-Id: exec-123" \
  -H "X-Trace-Id: trace-456" \
  -d '{"input": "hello"}'
```

**Headers:**
- `X-Execution-Id` - Required in warm mode, identifies the invocation
- `X-Trace-Id` - Optional, propagated to callback for distributed tracing
- `X-Callback-Url` - Optional, overrides CALLBACK_URL env var

## Development

### Run Tests

```bash
cd python-runtime
pip install -r requirements.txt
PYTHONPATH=src:tests pytest tests/ -v
```

### Run Locally

```bash
cd python-runtime
pip install -r requirements.txt
HANDLER_MODULE=tests.fixtures.handler \
EXECUTION_ID=test-exec \
PYTHONPATH=src:tests \
python -m nanofaas_runtime.app
```

### Build Docker Image

```bash
cd python-runtime
./build.sh

# Or with custom version:
VERSION=1.0.0 ./build.sh
```

## Comparison with Java Runtime

| Feature | Python Runtime | Java Runtime |
|---------|---------------|--------------|
| Framework | Flask + Gunicorn | Spring Boot |
| Handler Interface | `handle(dict) -> dict` | `FunctionHandler.handle(InvocationRequest)` |
| Startup Time | ~1s | ~2-3s (JVM) / <100ms (native) |
| Memory | ~50MB | ~100MB (JVM) / ~30MB (native) |
| Best For | Scripts, ML inference | Enterprise, complex logic |
