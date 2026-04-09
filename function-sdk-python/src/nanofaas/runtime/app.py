"""FastAPI-based runtime server for the nanofaas Python SDK.

This module exposes the ASGI application that hosts Python function handlers.
On startup it dynamically imports the module specified by the ``HANDLER_MODULE``
environment variable and expects to find exactly one function decorated with
:func:`nanofaas.sdk.decorator.nanofaas_function`.

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
    Execute the registered handler for a single invocation.
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
    Logs a warning if no handler was found; logs an error (with traceback) if
    the import itself fails.

    :param app: The FastAPI application instance (required by the lifespan
        protocol but unused directly).
    :type app: fastapi.FastAPI
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
    return {"status": "ok"}

@app.get("/metrics")
def metrics():
    return Response(content=generate_latest(), media_type=CONTENT_TYPE_LATEST)
