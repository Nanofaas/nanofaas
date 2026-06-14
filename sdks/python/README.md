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

## Documentation

All SDK modules include comprehensive pydoc-compatible docstrings with parameter descriptions, return types, and usage examples. Access documentation via `pydoc`:

### Core SDK Modules

```bash
# Handler decorator and registration
python -m pydoc nanofaas.sdk.decorator

# Execution context (execution ID, trace ID, logger)
python -m pydoc nanofaas.sdk.context

# Structured JSON logging configuration
python -m pydoc nanofaas.sdk.logging

# FastAPI runtime server and endpoints
python -m pydoc nanofaas.runtime.app
```

### Examples

View the `nanofaas_function` decorator documentation:
```bash
python -m pydoc nanofaas.sdk.decorator.nanofaas_function
```

View context management functions:
```bash
python -m pydoc nanofaas.sdk.context.get_execution_id
python -m pydoc nanofaas.sdk.context.set_context
```

Documentation is also available in IDEs (VS Code, PyCharm) via docstring tooltips and autocomplete hints.

## Testing

Run tests with `uv`:
```bash
uv run --extra test pytest tests/
```
