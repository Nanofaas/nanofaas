"""FastAPI-based runtime server for the nanofaas Python SDK.

This module exposes the ASGI application that hosts Python function handlers.
On startup it dynamically imports the module specified by the ``HANDLER_MODULE``
environment variable and expects to find exactly one function decorated with
:func:`~nanofaas.sdk.decorator.nanofaas_function`.

Environment variables
---------------------
HANDLER_MODULE
    Dotted module path of the user handler (e.g. ``mypackage.handler``).
FUNCTION_NAME
    Human-readable function name used in log messages and metric labels.
    Defaults to ``HANDLER_MODULE`` if not set.
CALLBACK_URL
    Base URL of the control-plane callback endpoint for async invocations.
EXECUTION_ID
    Fallback execution ID used when the ``X-Execution-Id`` header is absent.

Endpoints
---------
POST /invoke
    Execute the registered handler for a single invocation. Expects JSON request
    body with ``input`` field. Returns JSON response with handler output or error.
    Required headers: ``X-Execution-Id``. Optional headers: ``X-Trace-Id``,
    ``X-Callback-Url``. Response includes ``X-Cold-Start`` and ``X-Init-Duration-Ms``
    headers on first invocation.
GET  /health
    Liveness probe; always returns ``{"status": "ok"}``.
GET  /metrics
    Prometheus metrics in the text exposition format.
"""
import os
import importlib
import asyncio
import logging
import threading
import time
from contextlib import asynccontextmanager
from fastapi import FastAPI, Request, HTTPException, Header, BackgroundTasks
from fastapi.responses import JSONResponse
from fastapi.responses import Response
from nanofaas.sdk import context, decorator, logging as sdk_logging
import requests
from prometheus_client import Counter, Gauge, Histogram, generate_latest, CONTENT_TYPE_LATEST

# Set up logging early
sdk_logging.configure_logging()
logger = logging.getLogger(__name__)

@asynccontextmanager
async def lifespan(app: FastAPI):
    """ASGI lifespan handler: import the user handler module on startup.

    Imports the module identified by ``HANDLER_MODULE`` and verifies that it
    registered a handler via :func:`~nanofaas.sdk.decorator.nanofaas_function`.
    Logs a warning if no handler was found (via :func:`~nanofaas.sdk.decorator.get_registered_handler`);
    logs an error (with traceback) if the module import or any initialization fails.

    :param app: The FastAPI application instance (required by the lifespan
        protocol but unused directly).
    :type app: fastapi.FastAPI
    :returns: An async context manager that yields control to FastAPI after startup.
    :rtype: AsyncContextManager[None]
    """
    if HANDLER_MODULE:
        try:
            logger.info(f"Loading handler module: {HANDLER_MODULE}")
            importlib.import_module(HANDLER_MODULE)
            if not decorator.get_registered_handler():
                logger.warning(
                    f"Module {HANDLER_MODULE} loaded but no function decorated with @nanofaas_function"
                )
            else:
                logger.info("Successfully registered handler")
        except Exception as e:
            logger.error(f"Failed to load handler module {HANDLER_MODULE}: {e}", exc_info=True)
    yield

app = FastAPI(title="nanoFaaS Python Runtime", lifespan=lifespan)

CALLBACK_URL = os.environ.get('CALLBACK_URL', '')
DEFAULT_EXECUTION_ID = os.environ.get('EXECUTION_ID', '')
HANDLER_MODULE = os.environ.get('HANDLER_MODULE')
FUNCTION_NAME = os.environ.get('FUNCTION_NAME') or HANDLER_MODULE or "unknown"

RUNTIME_INVOCATIONS_TOTAL = Counter(
    "runtime_invocations_total",
    "Total invocations handled by the Python runtime",
    ["function", "success"],
)
RUNTIME_INVOCATION_DURATION_SECONDS = Histogram(
    "runtime_invocation_duration_seconds",
    "Invocation duration in seconds (Python runtime)",
    ["function"],
)
RUNTIME_IN_FLIGHT = Gauge(
    "runtime_in_flight",
    "In-flight invocations (Python runtime)",
    ["function"],
)
RUNTIME_INIT_DURATION_SECONDS = Histogram(
    "runtime_init_duration_seconds",
    "Container init duration until first invocation (Python runtime)",
    ["function"],
)
RUNTIME_COLD_START_TOTAL = Counter(
    "runtime_cold_start_total",
    "Total cold start invocations (Python runtime)",
    ["function"],
)

CONTAINER_START_TIME = time.monotonic()
_first_invocation = True
# threading.Lock is intentional: the critical section is a non-yielding boolean swap
# (no awaits), so it is safe and avoids the overhead of an asyncio.Lock acquire.
_cold_start_lock = threading.Lock()

async def send_callback(callback_url: str, execution_id: str, trace_id: str | None, result: dict):
    """Send an invocation result to the control-plane callback endpoint.

    Performs up to three HTTP POST attempts with exponential-ish back-off
    (0.1 s then 0.5 s, then a final immediate attempt). The HTTP call is
    offloaded to a thread-pool via :func:`asyncio.to_thread` to avoid
    blocking the event loop.

    :param callback_url: Base URL of the control-plane; trailing slash is
        stripped automatically.
    :type callback_url: str
    :param execution_id: Unique identifier of the execution being completed;
        appended to *callback_url* as ``/<execution_id>:complete``.
    :type execution_id: str
    :param trace_id: Optional distributed-tracing ID forwarded as the
        ``X-Trace-Id`` request header.
    :type trace_id: str | None
    :param result: JSON-serialisable result payload with keys ``success``,
        ``output``, and ``error``.
    :type result: dict
    """
    if not callback_url:
        return

    url = f"{callback_url.rstrip('/')}/{execution_id}:complete"
    headers = {"Content-Type": "application/json"}
    if trace_id:
        headers["X-Trace-Id"] = trace_id

    logger.info(f"Sending callback to {url}")
    delays = [0.1, 0.5]
    for attempt, delay in enumerate(delays):
        try:
            resp = await asyncio.to_thread(
                requests.post, url, json=result, headers=headers, timeout=5
            )
            if resp.status_code < 400:
                logger.info("Callback sent successfully")
                return
            logger.warning(f"Callback failed with status {resp.status_code} (attempt {attempt + 1})")
        except Exception as e:
            logger.warning(f"Callback error: {e} (attempt {attempt + 1})")
        await asyncio.sleep(delay)

    # Final attempt (no sleep after)
    try:
        resp = await asyncio.to_thread(
            requests.post, url, json=result, headers=headers, timeout=5
        )
        if resp.status_code < 400:
            logger.info("Callback sent successfully")
            return
        logger.warning(f"Callback failed with status {resp.status_code} (attempt {len(delays) + 1})")
    except Exception as e:
        logger.warning(f"Callback error: {e} (attempt {len(delays) + 1})")

    logger.error("Callback failed after all retries")

@app.post("/invoke")
async def invoke(
    request: Request,
    background_tasks: BackgroundTasks,
    x_execution_id: str | None = Header(None),
    x_trace_id: str | None = Header(None),
    x_callback_url: str | None = Header(None)
):
    """Handle a single function invocation request.

    Reads the JSON request body, extracts the ``input`` field, and calls the
    registered handler. Both synchronous and asynchronous handlers are
    supported. On success, returns the handler output as JSON; on failure,
    returns HTTP 500 with the exception message.

    Cold-start detection: the first invocation appends ``X-Cold-Start: true``
    and ``X-Init-Duration-Ms`` to the response headers and increments the
    ``runtime_cold_start_total`` Prometheus counter.

    When a callback URL is available (from the ``X-Callback-Url`` header or
    the ``CALLBACK_URL`` environment variable), the result is forwarded
    asynchronously via :func:`send_callback` as a background task.

    :param request: The incoming FastAPI request object.
    :type request: fastapi.Request
    :param background_tasks: FastAPI background task registry used for async
        callback dispatch.
    :type background_tasks: fastapi.BackgroundTasks
    :param x_execution_id: Value of the ``X-Execution-Id`` header.
    :type x_execution_id: str | None
    :param x_trace_id: Value of the ``X-Trace-Id`` header.
    :type x_trace_id: str | None
    :param x_callback_url: Value of the ``X-Callback-Url`` header; overrides
        ``CALLBACK_URL`` for this request.
    :type x_callback_url: str | None
    :returns: JSON response with the handler output, or an error body on
        failure.
    :rtype: fastapi.responses.JSONResponse
    :raises HTTPException: 400 if no execution ID is available; 500 if no
        handler is registered.
    """
    execution_id = x_execution_id or DEFAULT_EXECUTION_ID
    trace_id = x_trace_id
    callback_url = x_callback_url or CALLBACK_URL
    
    if not execution_id:
        raise HTTPException(status_code=400, detail="Execution ID required")
        
    context.set_context(execution_id, trace_id)
    handler = decorator.get_registered_handler()
    
    if not handler:
        logger.error("No handler registered")
        raise HTTPException(status_code=500, detail="No function registered with @nanofaas_function")

    global _first_invocation
    with _cold_start_lock:
        is_cold_start = _first_invocation
        _first_invocation = False

    start = time.perf_counter()
    RUNTIME_IN_FLIGHT.labels(function=FUNCTION_NAME).inc()
    try:
        payload = await request.json()
        # Java SDK expects the whole body as InvocationRequest, which has 'input' and 'metadata'
        # The existing python-runtime app.py used request.get_json() which is the whole body
        # Let's be consistent with Java SDK: it passes 'input' to the handler
        input_data = payload.get("input") if isinstance(payload, dict) else payload
        
        logger.info(f"Invoking handler for execution {execution_id}")
        
        if asyncio.iscoroutinefunction(handler):
            output = await handler(input_data)
        else:
            output = handler(input_data)

        RUNTIME_INVOCATIONS_TOTAL.labels(function=FUNCTION_NAME, success="true").inc()
        result = {"success": True, "output": output, "error": None}

        if callback_url:
            background_tasks.add_task(send_callback, callback_url, execution_id, trace_id, result)

        headers = {}
        if is_cold_start:
            init_duration_ms = int((time.monotonic() - CONTAINER_START_TIME) * 1000)
            headers["X-Cold-Start"] = "true"
            headers["X-Init-Duration-Ms"] = str(init_duration_ms)
            RUNTIME_COLD_START_TOTAL.labels(function=FUNCTION_NAME).inc()
            RUNTIME_INIT_DURATION_SECONDS.labels(function=FUNCTION_NAME).observe(init_duration_ms / 1000.0)

        return JSONResponse(content=output if isinstance(output, (dict, list)) else {"result": output}, headers=headers)
    except Exception as e:
        logger.exception(f"Handler error in execution {execution_id}: {e}")
        RUNTIME_INVOCATIONS_TOTAL.labels(function=FUNCTION_NAME, success="false").inc()
        error_result = {
            "success": False, 
            "output": None, 
            "error": {"code": "HANDLER_ERROR", "message": str(e)}
        }
        if callback_url:
            background_tasks.add_task(send_callback, callback_url, execution_id, trace_id, error_result)
            
        return JSONResponse(status_code=500, content={"error": str(e)})
    finally:
        elapsed = time.perf_counter() - start
        RUNTIME_INVOCATION_DURATION_SECONDS.labels(function=FUNCTION_NAME).observe(elapsed)
        RUNTIME_IN_FLIGHT.labels(function=FUNCTION_NAME).dec()

@app.get("/health")
def health():
    """Return a simple liveness status.

    :returns: A dictionary ``{"status": "ok"}``.
    :rtype: dict
    """
    return {"status": "ok"}

@app.get("/metrics")
def metrics():
    """Expose Prometheus metrics in the text exposition format.

    :returns: A plain-text response containing all registered metric families.
    :rtype: fastapi.responses.Response
    """
    return Response(content=generate_latest(), media_type=CONTENT_TYPE_LATEST)
