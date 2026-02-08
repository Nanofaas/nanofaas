# nanofaas Python SDK

SDK for developing and running Python functions on the nanoFaaS platform.

## Features

- **Context Management**: Automatic propagation of `execution_id` and `trace_id`.
- **Structured Logging**: Built-in JSON logging compatible with nanoFaaS observability.
- **FastAPI Runtime**: High-performance HTTP runtime for one-shot and warm execution.
- **Async Support**: Native support for `async def` handlers.

## Usage

### 1. Create a Handler

```python
from nanofaas.sdk import nanofaas_function, context

logger = context.get_logger(__name__)

@nanofaas_function
def handle(input_data):
    exec_id = context.get_execution_id()
    logger.info(f"Processing execution {exec_id}")
    
    return {"echo": input_data}
```

### 2. Configuration

| Environment Variable | Description |
|----------------------|-------------|
| `HANDLER_MODULE` | Python module containing the decorated function |
| `CALLBACK_URL` | nanoFaaS control-plane callback endpoint |
| `EXECUTION_ID` | Default execution ID for one-shot mode |

### 3. Local Development

Install dependencies:
```bash
uv pip install nanofaas-sdk
```

Run the runtime:
```bash
HANDLER_MODULE=my_handler uv run -m uvicorn nanofaas.runtime.app:app --port 8080
```

## Testing

Run tests with `uv`:
```bash
uv run --extra test pytest tests/
```
