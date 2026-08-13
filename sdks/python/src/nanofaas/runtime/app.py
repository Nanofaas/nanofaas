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
import json
import logging
import threading
import time
from contextlib import asynccontextmanager
from fastapi import FastAPI, Request, HTTPException, Header, BackgroundTasks
from fastapi.responses import JSONResponse
from fastapi.responses import Response
from nanofaas.sdk import context, decorator, logging as sdk_logging
from nanofaas.sdk.response import HandlerResponse
from typing import Annotated
import requests
from prometheus_client import (
    CONTENT_TYPE_LATEST,
    REGISTRY,
    Counter,
    Gauge,
    Histogram,
    generate_latest,
)

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
            logger.exception(f"Failed to load handler module {HANDLER_MODULE}: {e}")
    yield

app = FastAPI(title="nanoFaaS Python Runtime", lifespan=lifespan)

CALLBACK_URL = os.environ.get('CALLBACK_URL', '')
DEFAULT_EXECUTION_ID = os.environ.get('EXECUTION_ID', '')
DEFAULT_TRACE_ID = os.environ.get('TRACE_ID', '')
HANDLER_TIMEOUT_SECONDS = float(os.environ.get('NANOFAAS_HANDLER_TIMEOUT', '30000')) / 1000.0
HANDLER_MODULE = os.environ.get('HANDLER_MODULE')
FUNCTION_NAME = os.environ.get('FUNCTION_NAME') or HANDLER_MODULE or "unknown"


def _collector(factory, name: str, documentation: str, labelnames: list[str]):
    try:
        return factory(name, documentation, labelnames)
    except ValueError as exc:
        existing = getattr(REGISTRY, "_names_to_collectors", {}).get(name)
        if (
            existing is not None
            and isinstance(existing, factory)
            and tuple(getattr(existing, "_labelnames", ())) == tuple(labelnames)
        ):
            return existing
        raise exc


RUNTIME_INVOCATIONS_TOTAL = _collector(
    Counter,
    "runtime_invocations_total",
    "Total invocations handled by the Python runtime",
    ["function", "success"],
)
RUNTIME_INVOCATION_DURATION_SECONDS = _collector(
    Histogram,
    "runtime_invocation_duration_seconds",
    "Invocation duration in seconds (Python runtime)",
    ["function"],
)
RUNTIME_IN_FLIGHT = _collector(
    Gauge,
    "runtime_in_flight",
    "In-flight invocations (Python runtime)",
    ["function"],
)
RUNTIME_INIT_DURATION_SECONDS = _collector(
    Histogram,
    "runtime_init_duration_seconds",
    "Container init duration until first invocation (Python runtime)",
    ["function"],
)
RUNTIME_COLD_START_TOTAL = _collector(
    Counter,
    "runtime_cold_start_total",
    "Total cold start invocations (Python runtime)",
    ["function"],
)

# Mirror of ResponseHeaderPolicy.ALLOWED_RESPONSE_HEADERS (platform/common). Keep in sync.
_ALLOWED_RESPONSE_HEADERS = {
    "content-type", "location", "cache-control", "etag",
    "content-disposition", "content-language", "retry-after", "vary",
}


def _filter_response_headers(raw: dict[str, str] | None, execution_id: str | None = None) -> dict[str, str]:
    """Filter handler-supplied response headers down to the allow-list.

    At most one entry survives per header name, compared case-insensitively: HTTP header names are
    case-insensitive, so emitting both ``Content-Type`` and ``content-type`` would put two colliding
    entries on the response. The first occurrence wins and keeps its original casing, which is part
    of the public ``InvocationResponse.headers`` contract. Mirrors
    ``ResponseHeaderPolicy.filterAllowedHeaders`` in platform/common — keep the two in sync.
    """
    if not raw:
        return {}
    allowed: dict[str, str] = {}
    seen: set[str] = set()
    for key, value in raw.items():
        lower_key = key.lower()
        if lower_key in _ALLOWED_RESPONSE_HEADERS and lower_key not in seen:
            seen.add(lower_key)
            allowed[key] = value
    if len(allowed) != len(raw):
        dropped = [k for k in raw if k not in allowed]
        logger.warning(f"Dropped response header(s) {dropped} for execution {execution_id}")
    return allowed


CONTAINER_START_TIME = time.monotonic()
_first_invocation = True
# threading.Lock is intentional: the critical section is a non-yielding boolean swap
# (no awaits), so it is safe and avoids the overhead of an asyncio.Lock acquire.
_cold_start_lock = threading.Lock()
_callback_slots = threading.BoundedSemaphore(128)

async def send_callback(
    callback_url: str,
    execution_id: str,
    trace_id: str | None,
    result: dict,
    dispatch_attempt: str | None = None,
):
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
    :returns: None.
    :rtype: None
    """
    if not callback_url:
        return

    url = f"{callback_url.rstrip('/')}/{execution_id}:complete"
    headers = {"Content-Type": "application/json"}
    if trace_id:
        headers["X-Trace-Id"] = trace_id
    if dispatch_attempt:
        headers["X-Dispatch-Attempt"] = dispatch_attempt

    logger.info(f"Sending callback to {url}")
    delays = [0.1, 0.5]
    for attempt in range(3):
        try:
            resp = await asyncio.to_thread(
                requests.post, url, json=result, headers=headers, timeout=5
            )
            if resp.status_code < 400:
                logger.info("Callback sent successfully")
                return
            if 400 <= resp.status_code < 500 and resp.status_code not in (408, 429):
                logger.warning(f"Permanent callback failure with status {resp.status_code}")
                return
            logger.warning(f"Callback failed with status {resp.status_code} (attempt {attempt + 1})")
        except Exception as e:
            logger.warning(f"Callback error: {e} (attempt {attempt + 1})")
        if attempt < len(delays):
            await asyncio.sleep(delays[attempt])

    logger.error("Callback failed after all retries")


async def _send_callback_with_slot(*args):
    try:
        await send_callback(*args)
    finally:
        _callback_slots.release()


def _consume_cold_start() -> bool:
    """Atomically read and clear the first-invocation flag."""
    global _first_invocation
    with _cold_start_lock:
        is_cold_start = _first_invocation
        _first_invocation = False
    return is_cold_start


def _build_cold_start_headers(is_cold_start: bool) -> dict[str, str]:
    """Return cold-start response headers, incrementing the cold-start metrics."""
    if not is_cold_start:
        return {}
    init_duration_ms = int((time.monotonic() - CONTAINER_START_TIME) * 1000)
    RUNTIME_COLD_START_TOTAL.labels(function=FUNCTION_NAME).inc()
    RUNTIME_INIT_DURATION_SECONDS.labels(function=FUNCTION_NAME).observe(init_duration_ms / 1000.0)
    return {"X-Cold-Start": "true", "X-Init-Duration-Ms": str(init_duration_ms)}


def _fail_response(
    background_tasks: BackgroundTasks,
    callback_url: str | None,
    execution_id: str,
    trace_id: str | None,
    x_dispatch_attempt: str | None,
    *,
    status_code: int,
    error: dict,
    count_failure: bool,
) -> JSONResponse:
    """Build the shared failure response: error body, callback, optional failure counter."""
    if count_failure:
        RUNTIME_INVOCATIONS_TOTAL.labels(function=FUNCTION_NAME, success="false").inc()
    if callback_url:
        _schedule_callback(
            background_tasks,
            callback_url,
            execution_id,
            trace_id,
            {"success": False, "output": None, "error": error},
            x_dispatch_attempt,
        )
    return JSONResponse(status_code=status_code, content={"error": error})


def _schedule_callback(background_tasks: BackgroundTasks, *args) -> bool:
    if not _callback_slots.acquire(blocking=False):
        logger.warning("Dropping callback because the callback queue is full")
        return False
    background_tasks.add_task(_send_callback_with_slot, *args)
    return True

@app.post(
    "/invoke",
    responses={
        400: {"description": "Execution ID required"},
        500: {"description": "No function registered with @nanofaas_function"},
    },
)
async def invoke(
    request: Request,
    background_tasks: BackgroundTasks,
    x_execution_id: Annotated[str | None, Header()] = None,
    x_trace_id: Annotated[str | None, Header()] = None,
    x_callback_url: Annotated[str | None, Header()] = None,
    x_dispatch_attempt: Annotated[str | None, Header()] = None,
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
    trace_id = x_trace_id or DEFAULT_TRACE_ID or None
    callback_url = x_callback_url or CALLBACK_URL
    
    if not execution_id:
        raise HTTPException(status_code=400, detail="Execution ID required")
        
    context.set_context(execution_id, trace_id)
    handler = decorator.get_registered_handler()
    
    if not handler:
        logger.error("No handler registered")
        raise HTTPException(status_code=500, detail="No function registered with @nanofaas_function")

    is_cold_start = _consume_cold_start()

    start = time.perf_counter()
    RUNTIME_IN_FLIGHT.labels(function=FUNCTION_NAME).inc()
    try:
        payload = await request.json()
        # Keep the Python runtime aligned with the Java InvocationRequest contract.
        # Handlers receive the input field rather than the transport envelope.
        input_data = payload.get("input") if isinstance(payload, dict) else payload
        # Request headers are captured and filtered by the control plane and ride in the
        # body; this runtime's own HTTP headers belong to the control-plane hop.
        context.set_headers(payload.get("headers") if isinstance(payload, dict) else None)

        logger.info(f"Invoking handler for execution {execution_id}")
        
        invocation = handler(input_data) if asyncio.iscoroutinefunction(handler) \
            else asyncio.to_thread(handler, input_data)
        output = await asyncio.wait_for(invocation, timeout=HANDLER_TIMEOUT_SECONDS)

        response_status = 200
        response_headers = _build_cold_start_headers(is_cold_start)
        response_body = output
        callback_status_code = None
        callback_headers = None
        callback_encoding = None

        if isinstance(output, HandlerResponse):
            if 200 <= output.status_code <= 599:
                response_status = output.status_code
                allowed = _filter_response_headers(output.headers, execution_id)
                response_headers = {**response_headers, **allowed, "X-NanoFaaS-Function-Status": "true"}
                if output.encoding:
                    response_headers["X-NanoFaaS-Encoding"] = output.encoding
                response_body = output.output
                callback_status_code = output.status_code
                callback_headers = allowed
                callback_encoding = output.encoding
            else:
                logger.warning(f"Handler returned invalid statusCode {output.status_code} for execution {execution_id}, treating as platform error")
                RUNTIME_INVOCATIONS_TOTAL.labels(function=FUNCTION_NAME, success="false").inc()
                if callback_url:
                    _schedule_callback(
                        background_tasks, callback_url, execution_id, trace_id,
                        {"success": False, "output": None,
                         "error": {"code": "OUTPUT_SERIALIZATION_ERROR",
                                   "message": f"Handler returned invalid statusCode: {output.status_code}"}},
                        x_dispatch_attempt,
                    )
                return JSONResponse(status_code=500, content={
                    "error": f"Handler returned invalid statusCode: {output.status_code}"})

        RUNTIME_INVOCATIONS_TOTAL.labels(function=FUNCTION_NAME, success="true").inc()
        result = {"success": True, "output": response_body, "error": None}
        if callback_status_code is not None:
            result["statusCode"] = callback_status_code
            result["headers"] = callback_headers
            result["encoding"] = callback_encoding

        if callback_url:
            _schedule_callback(
                background_tasks, callback_url, execution_id, trace_id, result, x_dispatch_attempt
            )

        return JSONResponse(
            status_code=response_status,
            # ponytail: the envelope path emits output verbatim so Java and Python agree on
            # the wire; the plain path keeps today's {"result": ...} wrapping untouched for
            # backward compatibility.
            content=response_body
            if callback_status_code is not None or isinstance(response_body, (dict, list))
            else {"result": response_body},
            headers=response_headers,
        )
    except json.JSONDecodeError:
        return _fail_response(
            background_tasks, callback_url, execution_id, trace_id, x_dispatch_attempt,
            status_code=400, count_failure=False,
            error={"code": "INVALID_JSON", "message": "Request body must be valid JSON"},
        )
    except asyncio.TimeoutError:
        return _fail_response(
            background_tasks, callback_url, execution_id, trace_id, x_dispatch_attempt,
            status_code=504, count_failure=True,
            error={"code": "HANDLER_TIMEOUT", "message": "Handler exceeded configured timeout"},
        )
    except Exception as e:
        logger.exception(f"Handler error in execution {execution_id}: {e}")
        RUNTIME_INVOCATIONS_TOTAL.labels(function=FUNCTION_NAME, success="false").inc()
        error_result = {
            "success": False, 
            "output": None, 
            "error": {"code": "HANDLER_ERROR", "message": str(e)}
        }
        if callback_url:
            _schedule_callback(
                background_tasks, callback_url, execution_id, trace_id, error_result, x_dispatch_attempt
            )
            
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
