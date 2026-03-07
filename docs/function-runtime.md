# Function Runtime Design (Detailed)

## Purpose

Minimal HTTP server that accepts input from the control plane and returns output.
Designed for GraalVM native image with low cold-start.

## HTTP Contract

- POST /invoke
  - Headers:
    - X-Execution-Id
    - X-Trace-Id (optional)
    - Idempotency-Key (optional)
  - Body:
    - application/json or application/octet-stream
  - Response:
    - 200 with JSON or binary payload
    - 4xx for user errors, 5xx for runtime errors

## Runtime Environment

- Container image defined in FunctionSpec.
- Required env vars:
  - FUNCTION_NAME
  - EXECUTION_ID
  - TRACE_ID (optional)
  - INVOCATION_TIMEOUT_MS
  - FUNCTION_HANDLER (optional; required only when multiple FunctionHandler beans are registered)

## Execution Model

- One request per pod invocation (Job model).
- Function code handles request and returns response.
- No local queue; no retry logic in runtime.
- Runtime resolves a single FunctionHandler bean; multiple handlers require FUNCTION_HANDLER env.

## Error Model

- 400: missing or blank execution ID
- 422: function domain error (optional)
- 500: handler threw an exception; response body is `{"error": "<message>"}` with a stable fallback of `"Handler execution failed"` when the exception carries no message
- 504: handler exceeded `INVOCATION_TIMEOUT_MS`; response body is `{"error": "Handler timed out"}`

## Observability

- Logs include executionId and traceId.
- Optional: expose /actuator/prometheus when running as service (not required for Job).

## Performance Guidelines

- Keep dependencies minimal.
- Avoid reflection; use Spring AOT hints where needed.
- Keep payload parsing lightweight.
