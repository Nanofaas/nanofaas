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
NANOFAAS_HANDLER_TIMEOUT
    Finite positive handler wait timeout in milliseconds. A synchronous
    handler thread may remain physically active after this wait expires.
NANOFAAS_MAX_CONCURRENT_HANDLERS
    Positive bound on admitted physical handler executions. Defaults to 32.
NANOFAAS_CALLBACK_WORKERS
    Positive number of runtime-owned blocking callback workers. Defaults to 2.
NANOFAAS_MAX_PENDING_CALLBACKS
    Positive bound on callback work admitted before executor submission.
    Defaults to 128.
NANOFAAS_MAX_INPUT_BYTES
    Positive byte limit for an invocation request body. Defaults to 1 MiB.
NANOFAAS_MAX_OUTPUT_BYTES
    Positive byte limit for a handler output. Defaults to 1 MiB.
NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES
    Positive byte limit for one serialized callback. Defaults to 2 MiB.
NANOFAAS_MAX_PENDING_CALLBACK_BYTES
    Positive aggregate reservation for pending callbacks. Defaults to 16 MiB.
NANOFAAS_BODY_READ_TIMEOUT
    Finite positive request-body read timeout in milliseconds. Defaults to 5000.
NANOFAAS_CALLBACK_ATTEMPT_TIMEOUT
    Finite positive callback HTTP attempt timeout in milliseconds. Defaults to 5000.
NANOFAAS_CALLBACK_MAX_ATTEMPTS
    Positive callback delivery attempt count. Defaults to 3.
NANOFAAS_SHUTDOWN_TIMEOUT
    Finite positive physical-drain deadline in milliseconds. Defaults to 5000;
    non-cooperative Python threads are reported because they cannot be killed safely.

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
import contextvars
import inspect
import json
import logging
import math
import threading
import time
from concurrent.futures import Future, ThreadPoolExecutor
from contextlib import asynccontextmanager
from dataclasses import dataclass
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
    try:
        yield
    finally:
        report = await _runtime_work.shutdown(SHUTDOWN_TIMEOUT_SECONDS)
        app.state.runtime_shutdown_report = report
        if not report.drained:
            logger.warning(
                "Runtime shutdown reached its bound with non-cooperative work still active: "
                f"handlers={report.active_handlers}, callbacks={report.pending_callbacks}, "
                f"callback_workers={report.active_callback_workers}"
            )

app = FastAPI(title="nanoFaaS Python Runtime", lifespan=lifespan)

CALLBACK_URL = os.environ.get('CALLBACK_URL', '')
DEFAULT_EXECUTION_ID = os.environ.get('EXECUTION_ID', '')
DEFAULT_TRACE_ID = os.environ.get('TRACE_ID', '')
HANDLER_TIMEOUT_SECONDS = float(os.environ.get('NANOFAAS_HANDLER_TIMEOUT', '30000')) / 1000.0
MAX_CONCURRENT_HANDLERS = int(os.environ.get('NANOFAAS_MAX_CONCURRENT_HANDLERS', '32'))
CALLBACK_WORKERS = int(os.environ.get('NANOFAAS_CALLBACK_WORKERS', '2'))
MAX_PENDING_CALLBACKS = int(os.environ.get('NANOFAAS_MAX_PENDING_CALLBACKS', '128'))
MAX_INPUT_BYTES = int(os.environ.get('NANOFAAS_MAX_INPUT_BYTES', str(1024 * 1024)))
MAX_OUTPUT_BYTES = int(os.environ.get('NANOFAAS_MAX_OUTPUT_BYTES', str(1024 * 1024)))
MAX_CALLBACK_BYTES = int(
    os.environ.get('NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES', str(2 * 1024 * 1024))
)
MAX_PENDING_CALLBACK_BYTES = int(
    os.environ.get('NANOFAAS_MAX_PENDING_CALLBACK_BYTES', str(16 * 1024 * 1024))
)
BODY_READ_TIMEOUT_SECONDS = float(os.environ.get('NANOFAAS_BODY_READ_TIMEOUT', '5000')) / 1000.0
CALLBACK_ATTEMPT_TIMEOUT_SECONDS = (
    float(os.environ.get('NANOFAAS_CALLBACK_ATTEMPT_TIMEOUT', '5000')) / 1000.0
)
CALLBACK_MAX_ATTEMPTS = int(os.environ.get('NANOFAAS_CALLBACK_MAX_ATTEMPTS', '3'))
SHUTDOWN_TIMEOUT_SECONDS = float(os.environ.get('NANOFAAS_SHUTDOWN_TIMEOUT', '5000')) / 1000.0
HANDLER_MODULE = os.environ.get('HANDLER_MODULE')
FUNCTION_NAME = os.environ.get('FUNCTION_NAME') or HANDLER_MODULE or "unknown"

for _setting_name, _setting_value in (
    ("NANOFAAS_MAX_CONCURRENT_HANDLERS", MAX_CONCURRENT_HANDLERS),
    ("NANOFAAS_CALLBACK_WORKERS", CALLBACK_WORKERS),
    ("NANOFAAS_MAX_PENDING_CALLBACKS", MAX_PENDING_CALLBACKS),
    ("NANOFAAS_MAX_INPUT_BYTES", MAX_INPUT_BYTES),
    ("NANOFAAS_MAX_OUTPUT_BYTES", MAX_OUTPUT_BYTES),
    ("NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES", MAX_CALLBACK_BYTES),
    ("NANOFAAS_MAX_PENDING_CALLBACK_BYTES", MAX_PENDING_CALLBACK_BYTES),
    ("NANOFAAS_CALLBACK_MAX_ATTEMPTS", CALLBACK_MAX_ATTEMPTS),
):
    if _setting_value <= 0:
        raise ValueError(f"{_setting_name} must be positive")
for _setting_name, _setting_value in (
    ("NANOFAAS_HANDLER_TIMEOUT", HANDLER_TIMEOUT_SECONDS),
    ("NANOFAAS_BODY_READ_TIMEOUT", BODY_READ_TIMEOUT_SECONDS),
    ("NANOFAAS_CALLBACK_ATTEMPT_TIMEOUT", CALLBACK_ATTEMPT_TIMEOUT_SECONDS),
    ("NANOFAAS_SHUTDOWN_TIMEOUT", SHUTDOWN_TIMEOUT_SECONDS),
):
    if not math.isfinite(_setting_value) or _setting_value <= 0:
        raise ValueError(f"{_setting_name} must be finite and positive")
if MAX_CALLBACK_BYTES > MAX_PENDING_CALLBACK_BYTES:
    raise ValueError(
        "NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES must not exceed "
        "NANOFAAS_MAX_PENDING_CALLBACK_BYTES"
    )


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
RUNTIME_ACTIVE_HANDLERS = _collector(
    Gauge,
    "runtime_active_handlers",
    "Handlers whose synchronous thread or asynchronous task has not physically completed",
    ["function"],
)
RUNTIME_HANDLER_WAIT_TIMEOUTS_TOTAL = _collector(
    Counter,
    "runtime_handler_wait_timeouts_total",
    "HTTP handler waits that reached their configured timeout",
    ["function"],
)
RUNTIME_HANDLER_SATURATION_TOTAL = _collector(
    Counter,
    "runtime_handler_saturation_total",
    "Invocations refused before handler executor submission",
    ["function"],
)
RUNTIME_PENDING_CALLBACKS = _collector(
    Gauge,
    "runtime_pending_callbacks",
    "Callbacks admitted to runtime-owned background capacity",
    ["function"],
)
RUNTIME_PENDING_CALLBACK_BYTES = _collector(
    Gauge,
    "runtime_pending_callback_bytes",
    "Bytes reserved by callbacks awaiting or performing delivery",
    ["function"],
)
RUNTIME_ACTIVE_CALLBACK_WORKERS = _collector(
    Gauge,
    "runtime_active_callback_workers",
    "Runtime-owned callback workers currently executing blocking HTTP calls",
    ["function"],
)
RUNTIME_CALLBACK_DELIVERY_FAILURES_TOTAL = _collector(
    Counter,
    "runtime_callback_delivery_failures_total",
    "Callbacks that exhausted all configured delivery attempts",
    ["function"],
)


CALLBACK_SATURATED = "callback saturated"


class HandlerAdmissionError(RuntimeError):
    """Raised when handler capacity is full or the runtime is stopping."""


class PayloadTooLargeError(ValueError):
    """Raised before retaining a serialized value beyond its configured cap."""


class BodyReadTimeoutError(TimeoutError):
    """Raised when the finite request-body read deadline expires."""


async def _await_concurrent_future(work: Future):
    """Propagate executor completion explicitly without cancelling physical work."""
    loop = asyncio.get_running_loop()
    waiter = loop.create_future()

    def copy_completion(completed: Future) -> None:
        def finish_waiter() -> None:
            if waiter.done():
                return
            if completed.cancelled():
                waiter.cancel()
                return
            error = completed.exception()
            if error is not None:
                waiter.set_exception(error)
            else:
                waiter.set_result(completed.result())

        try:
            loop.call_soon_threadsafe(finish_waiter)
        except RuntimeError:
            # A closed event loop has no live awaiter; physical ownership remains
            # with RuntimeWorkManager's concurrent-future done callback.
            pass

    work.add_done_callback(copy_completion)
    return await waiter


@dataclass(frozen=True)
class RuntimeWorkSnapshot:
    active_handlers: int
    timed_out_waits: int
    pending_callbacks: int
    pending_callback_bytes: int
    active_callback_workers: int


@dataclass(frozen=True)
class RuntimeShutdownReport:
    drained: bool
    active_handlers: int
    pending_callbacks: int
    pending_callback_bytes: int
    active_callback_workers: int


class CallbackReservation:
    """Exactly-once ownership token for one pending callback's retained bytes."""

    def __init__(self, manager, retained_bytes: int, *, preserve_on_stop: bool):
        self._manager = manager
        self.retained_bytes = retained_bytes
        self._preserve_on_stop = preserve_on_stop
        self._started = False
        self._closed = False
        self._release_requested = False
        self._physical_holds = 0

    def start(self) -> bool:
        with self._manager._lock:
            if self._closed:
                return False
            self._started = True
            return True

    def resize(self, retained_bytes: int) -> None:
        self._manager._resize_callback(self, retained_bytes)

    def release(self) -> None:
        self._manager._release_callback(self)


class HandlerExecution:
    """A retained physical handler handle whose wait may end before its work."""

    def __init__(self, work, *, async_task: bool):
        self.work = work
        self.async_task = async_task

    async def wait(self, timeout: float):  # NOSONAR (python:S7483): the budget spans several phases
        awaitable = (
            asyncio.shield(self.work)
            if self.async_task
            else asyncio.shield(_await_concurrent_future(self.work))
        )
        try:
            return await asyncio.wait_for(awaitable, timeout=timeout)
        except asyncio.TimeoutError:
            self.request_cancel()
            if self.async_task:
                await asyncio.sleep(0)
            raise

    def request_cancel(self) -> None:
        if self.async_task:
            self.work.cancel()


def _cancel_tasks(tasks) -> None:
    """Cancel asyncio tasks from any thread, on the loop that owns each of them."""
    for task in tasks:
        task_loop = task.get_loop()
        if task_loop.is_running():
            task_loop.call_soon_threadsafe(task.cancel)
        else:
            task.cancel()


class RuntimeWorkManager:
    """Own bounded handler and callback work and report physical drain truthfully."""

    def __init__(
        self,
        max_handlers: int,
        callback_workers: int,
        max_pending_callbacks: int,
        max_pending_callback_bytes: int,
    ):
        self._handler_slots = threading.BoundedSemaphore(max_handlers)
        self._handler_executor = ThreadPoolExecutor(
            max_workers=max_handlers,
            thread_name_prefix="nanofaas-handler",
        )
        self._callback_submit_slots = threading.BoundedSemaphore(max_pending_callbacks)
        self._callback_executor = ThreadPoolExecutor(
            max_workers=callback_workers,
            thread_name_prefix="nanofaas-callback",
        )
        self._lock = threading.Condition()
        self._accepting = True
        self._handler_work: set[Future | asyncio.Task] = set()
        self._callback_work: set[Future] = set()
        self._callback_tasks: set[asyncio.Task] = set()
        self._pending_callbacks = 0
        self._pending_callback_bytes = 0
        self._max_pending_callbacks = max_pending_callbacks
        self._max_pending_callback_bytes = max_pending_callback_bytes
        self._callback_reservations: set[CallbackReservation] = set()
        self._active_callback_workers = 0
        self._timed_out_waits = 0
        self._drain_waiters: set[tuple[asyncio.AbstractEventLoop, asyncio.Event]] = set()

    def start_handler(self, handler, input_data) -> HandlerExecution:
        if not self._handler_slots.acquire(blocking=False):
            raise HandlerAdmissionError("saturated")
        try:
            with self._lock:
                if not self._accepting:
                    raise HandlerAdmissionError("stopping")
                if inspect.iscoroutinefunction(handler):
                    work = asyncio.create_task(handler(input_data))
                    async_task = True
                else:
                    invocation_context = contextvars.copy_context()
                    work = self._handler_executor.submit(
                        invocation_context.run, handler, input_data
                    )
                    async_task = False
                self._handler_work.add(work)
        except BaseException:
            self._handler_slots.release()
            raise

        RUNTIME_ACTIVE_HANDLERS.labels(function=FUNCTION_NAME).inc()
        work.add_done_callback(self._handler_completed)
        return HandlerExecution(work, async_task=async_task)

    def record_wait_timeout(self) -> None:
        with self._lock:
            self._timed_out_waits += 1
        RUNTIME_HANDLER_WAIT_TIMEOUTS_TOTAL.labels(function=FUNCTION_NAME).inc()

    def reserve_callback(
        self, retained_bytes: int, *, preserve_on_stop: bool = False
    ) -> CallbackReservation:
        with self._lock:
            if not self._accepting:
                raise HandlerAdmissionError("stopping")
            if (
                retained_bytes < 0
                or self._pending_callbacks >= self._max_pending_callbacks
                or retained_bytes > self._max_pending_callback_bytes - self._pending_callback_bytes
            ):
                raise HandlerAdmissionError(CALLBACK_SATURATED)
            reservation = CallbackReservation(
                self, retained_bytes, preserve_on_stop=preserve_on_stop
            )
            self._callback_reservations.add(reservation)
            self._pending_callbacks += 1
            self._pending_callback_bytes += retained_bytes
        RUNTIME_PENDING_CALLBACKS.labels(function=FUNCTION_NAME).inc()
        RUNTIME_PENDING_CALLBACK_BYTES.labels(function=FUNCTION_NAME).inc(retained_bytes)
        return reservation

    def _resize_callback(self, reservation: CallbackReservation, retained_bytes: int) -> None:
        with self._lock:
            if reservation._closed:
                return
            difference = retained_bytes - reservation.retained_bytes
            if retained_bytes < 0 or difference > self._max_pending_callback_bytes - self._pending_callback_bytes:
                raise HandlerAdmissionError(CALLBACK_SATURATED)
            reservation.retained_bytes = retained_bytes
            self._pending_callback_bytes += difference
        RUNTIME_PENDING_CALLBACK_BYTES.labels(function=FUNCTION_NAME).inc(difference)

    def _release_callback(self, reservation: CallbackReservation) -> None:
        with self._lock:
            if reservation._closed:
                return
            reservation._release_requested = True
            if reservation._physical_holds:
                return
            retained_bytes = self._close_callback_locked(reservation)
        RUNTIME_PENDING_CALLBACKS.labels(function=FUNCTION_NAME).dec()
        RUNTIME_PENDING_CALLBACK_BYTES.labels(function=FUNCTION_NAME).dec(retained_bytes)

    def _close_callback_locked(self, reservation: CallbackReservation) -> int:
        """Close one reservation while the manager condition is held."""
        reservation._closed = True
        self._callback_reservations.discard(reservation)
        retained_bytes = reservation.retained_bytes
        self._pending_callbacks -= 1
        self._pending_callback_bytes -= retained_bytes
        self._notify_drain_waiters_locked()
        return retained_bytes

    def _hold_callback_physical(self, reservation: CallbackReservation) -> None:
        if reservation._closed:
            raise HandlerAdmissionError("callback reservation closed")
        reservation._physical_holds += 1

    def _release_callback_physical(self, reservation: CallbackReservation) -> None:
        retained_bytes = None
        with self._lock:
            reservation._physical_holds -= 1
            if reservation._physical_holds == 0 and reservation._release_requested:
                retained_bytes = self._close_callback_locked(reservation)
        if retained_bytes is not None:
            RUNTIME_PENDING_CALLBACKS.labels(function=FUNCTION_NAME).dec()
            RUNTIME_PENDING_CALLBACK_BYTES.labels(function=FUNCTION_NAME).dec(retained_bytes)

    async def run_callback_call(
        self, callback, *args, callback_reservation: CallbackReservation | None = None, **kwargs
    ):
        if not self._callback_submit_slots.acquire(blocking=False):
            raise HandlerAdmissionError(CALLBACK_SATURATED)
        held_reservation = None
        try:
            with self._lock:
                if not self._accepting and callback_reservation is None:
                    raise HandlerAdmissionError("stopping")
                if callback_reservation is not None:
                    self._hold_callback_physical(callback_reservation)
                    held_reservation = callback_reservation
                work = self._callback_executor.submit(self._run_callback, callback, args, kwargs)
                self._callback_work.add(work)
        except BaseException:
            if held_reservation is not None:
                held_reservation._physical_holds -= 1
            self._callback_submit_slots.release()
            raise
        work.add_done_callback(
            lambda completed: self._callback_completed(completed, held_reservation)
        )
        return await _await_concurrent_future(work)

    def start_reserved_callback_task(
        self, reservation: CallbackReservation, callback, *args
    ) -> asyncio.Task:
        """Register terminal callback work already admitted before runtime stop."""
        with self._lock:
            if reservation._closed or reservation not in self._callback_reservations:
                raise HandlerAdmissionError("callback reservation closed")
            reservation._started = True
            task = asyncio.create_task(callback(*args))
            self._callback_tasks.add(task)
        task.add_done_callback(
            lambda completed: self._callback_task_completed(completed, reservation)
        )
        return task

    def snapshot(self) -> RuntimeWorkSnapshot:
        with self._lock:
            return RuntimeWorkSnapshot(
                active_handlers=len(self._handler_work),
                timed_out_waits=self._timed_out_waits,
                pending_callbacks=self._pending_callbacks,
                pending_callback_bytes=self._pending_callback_bytes,
                active_callback_workers=self._active_callback_workers,
            )

    async def shutdown(self, timeout: float) -> RuntimeShutdownReport:  # NOSONAR (python:S7483): the budget spans several phases
        """Stop admission and await physical drain up to ``timeout`` seconds.

        Cooperative async tasks can finish cancellation while this coroutine is
        suspended. Python cannot safely terminate a non-cooperative handler
        thread; such work remains visible in the returned report.
        """
        deadline = time.monotonic() + timeout
        loop = asyncio.get_running_loop()
        drain_event = asyncio.Event()
        with self._lock:
            self._accepting = False
            async_tasks = [work for work in self._handler_work if isinstance(work, asyncio.Task)]
            orphaned_reservations = [
                reservation
                for reservation in self._callback_reservations
                if not reservation._started and not reservation._preserve_on_stop
            ]
            self._drain_waiters.add((loop, drain_event))
        for reservation in orphaned_reservations:
            reservation.release()
        _cancel_tasks(async_tasks)
        self._handler_executor.shutdown(wait=False, cancel_futures=True)

        try:
            await self._await_drain(deadline, drain_event)
        finally:
            with self._lock:
                self._drain_waiters.discard((loop, drain_event))
                callback_tasks = list(self._callback_tasks)
            _cancel_tasks(task for task in callback_tasks if not task.get_loop().is_closed())
            self._callback_executor.shutdown(wait=False, cancel_futures=True)
        snapshot = self.snapshot()
        return RuntimeShutdownReport(
            drained=(
                snapshot.active_handlers == 0
                and snapshot.pending_callbacks == 0
                and snapshot.active_callback_workers == 0
            ),
            active_handlers=snapshot.active_handlers,
            pending_callbacks=snapshot.pending_callbacks,
            pending_callback_bytes=snapshot.pending_callback_bytes,
            active_callback_workers=snapshot.active_callback_workers,
        )

    def _has_live_work(self) -> bool:
        with self._lock:
            return bool(
                self._handler_work
                or self._callback_work
                or self._callback_tasks
                or self._pending_callbacks
            )

    async def _await_drain(self, deadline: float, drain_event: asyncio.Event) -> None:
        remaining = deadline - time.monotonic()
        while self._has_live_work() and remaining > 0:
            try:
                await asyncio.wait_for(drain_event.wait(), timeout=remaining)
            except asyncio.TimeoutError:
                return
            drain_event.clear()
            remaining = deadline - time.monotonic()

    def _handler_completed(self, work) -> None:
        with self._lock:
            if work not in self._handler_work:
                return
            self._handler_work.remove(work)
            self._handler_slots.release()
            self._notify_drain_waiters_locked()
        RUNTIME_ACTIVE_HANDLERS.labels(function=FUNCTION_NAME).dec()

    def _run_callback(self, callback, args, kwargs):
        with self._lock:
            self._active_callback_workers += 1
        RUNTIME_ACTIVE_CALLBACK_WORKERS.labels(function=FUNCTION_NAME).inc()
        try:
            return callback(*args, **kwargs)
        finally:
            with self._lock:
                self._active_callback_workers -= 1
                self._notify_drain_waiters_locked()
            RUNTIME_ACTIVE_CALLBACK_WORKERS.labels(function=FUNCTION_NAME).dec()

    def _callback_completed(
        self, work, reservation: CallbackReservation | None = None
    ) -> None:
        with self._lock:
            if work not in self._callback_work:
                return
            self._callback_work.remove(work)
            self._callback_submit_slots.release()
            self._notify_drain_waiters_locked()
        if reservation is not None:
            self._release_callback_physical(reservation)

    def _callback_task_completed(self, task, reservation: CallbackReservation) -> None:
        with self._lock:
            self._callback_tasks.discard(task)
            self._notify_drain_waiters_locked()
        reservation.release()

    def _notify_drain_waiters_locked(self) -> None:
        self._lock.notify_all()
        for loop, drain_event in tuple(self._drain_waiters):
            try:
                loop.call_soon_threadsafe(drain_event.set)
            except RuntimeError:
                self._drain_waiters.discard((loop, drain_event))


_runtime_work = RuntimeWorkManager(
    MAX_CONCURRENT_HANDLERS,
    CALLBACK_WORKERS,
    MAX_PENDING_CALLBACKS,
    MAX_PENDING_CALLBACK_BYTES,
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


def _preflight_json_scalars(value, limit: int, active_containers: set[int] | None = None) -> None:
    """Reject any scalar that alone cannot fit before JSON creates its escaped copy."""
    if isinstance(value, str):
        _preflight_json_string(value, limit)
        return
    if isinstance(value, int) and not isinstance(value, bool):
        if value.bit_length() > limit * 4:
            raise PayloadTooLargeError
        return
    if isinstance(value, (dict, list, tuple)):
        _preflight_json_container(value, limit, set() if active_containers is None else active_containers)


def _preflight_json_string(value: str, limit: int) -> None:
    encoded_size = 2
    for character in value:
        codepoint = ord(character)
        if character in ('"', "\\") or character in "\b\f\n\r\t":
            encoded_size += 2
        elif codepoint < 0x20:
            encoded_size += 6
        else:
            encoded_size += len(character.encode("utf-8"))
        if encoded_size > limit:
            raise PayloadTooLargeError


def _preflight_json_container(value, limit: int, active_containers: set[int]) -> None:
    identity = id(value)
    if identity in active_containers:
        return
    active_containers.add(identity)
    try:
        if isinstance(value, dict):
            for key, item in value.items():
                _preflight_json_scalars(key, limit, active_containers)
                _preflight_json_scalars(item, limit, active_containers)
        else:
            for item in value:
                _preflight_json_scalars(item, limit, active_containers)
    finally:
        active_containers.remove(identity)


def _encode_json_bounded(value, limit: int) -> bytes:
    """Encode compact UTF-8 JSON without retaining data beyond a finite cap."""
    _preflight_json_scalars(value, limit)
    encoded = bytearray()
    encoder = json.JSONEncoder(ensure_ascii=False, allow_nan=False, separators=(",", ":"))
    for chunk in encoder.iterencode(value):
        chunk_bytes = chunk.encode("utf-8")
        if len(encoded) + len(chunk_bytes) > limit:
            raise PayloadTooLargeError
        encoded.extend(chunk_bytes)
    return bytes(encoded)


def _reject_oversized_declared_length(request: Request) -> None:
    headers = getattr(request, "headers", {})
    content_length = headers.get("content-length") if headers is not None else None
    if content_length is None:
        return
    try:
        declared_length = int(content_length)
    except ValueError:
        return
    if declared_length > MAX_INPUT_BYTES:
        raise PayloadTooLargeError


async def _read_bounded_json(request: Request):
    _reject_oversized_declared_length(request)

    async def read():
        stream = getattr(request, "stream", None)
        if callable(stream):
            body = bytearray()
            async for chunk in stream():
                if len(body) + len(chunk) > MAX_INPUT_BYTES:
                    raise PayloadTooLargeError
                body.extend(chunk)
            return json.loads(body)

        payload = await request.json()
        _encode_json_bounded(payload, MAX_INPUT_BYTES)
        return payload

    try:
        async with asyncio.timeout(BODY_READ_TIMEOUT_SECONDS):
            return await read()
    except TimeoutError as error:
        raise BodyReadTimeoutError from error

async def send_callback(
    callback_url: str,
    execution_id: str,
    trace_id: str | None,
    result: dict | bytes,
    dispatch_attempt: str | None = None,
    *,
    callback_reservation: CallbackReservation | None = None,
):
    """Send an invocation result to the control-plane callback endpoint.

    Performs the configured number of HTTP POST attempts (three by default)
    with finite per-attempt timeout and bounded back-off. The HTTP call is
    offloaded to the runtime-owned callback executor to avoid blocking the
    event loop or consuming handler capacity.

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
    :type result: dict | bytes
    :returns: None.
    :rtype: None
    """
    if not callback_url:
        return

    url = f"{callback_url.rstrip('/')}/{execution_id}:complete"
    headers = _callback_headers(trace_id, dispatch_attempt)

    reservation = callback_reservation
    try:
        serialized_result = _serialize_callback_result(result)
        if reservation is None:
            reservation = _runtime_work.reserve_callback(len(serialized_result))
        else:
            reservation.resize(len(serialized_result))
        if not reservation.start():
            return

        logger.info(f"Sending callback to {url}")
        if not await _post_callback_with_retries(url, serialized_result, headers, reservation):
            logger.error("Callback failed after all retries")
            RUNTIME_CALLBACK_DELIVERY_FAILURES_TOTAL.labels(function=FUNCTION_NAME).inc()
    finally:
        if reservation is not None:
            reservation.release()


def _callback_headers(trace_id: str | None, dispatch_attempt: str | None) -> dict[str, str]:
    headers = {"Content-Type": "application/json"}
    if trace_id:
        headers["X-Trace-Id"] = trace_id
    if dispatch_attempt:
        headers["X-Dispatch-Attempt"] = dispatch_attempt
    return headers


def _serialize_callback_result(result: dict | bytes) -> bytes:
    if not isinstance(result, bytes):
        return _encode_json_bounded(result, MAX_CALLBACK_BYTES)
    if len(result) > MAX_CALLBACK_BYTES:
        raise PayloadTooLargeError
    return result


async def _post_callback_with_retries(
    url: str, serialized_result: bytes, headers: dict[str, str], reservation: CallbackReservation
) -> bool:
    """Deliver the callback; False only when every attempt was retryable and failed."""
    delays = [0.1, 0.5, 2.0]
    for attempt in range(CALLBACK_MAX_ATTEMPTS):
        try:
            resp = await _runtime_work.run_callback_call(
                requests.post,
                url,
                data=serialized_result,
                headers=headers,
                timeout=CALLBACK_ATTEMPT_TIMEOUT_SECONDS,
                callback_reservation=reservation,
            )
            if resp.status_code < 400:
                logger.info("Callback sent successfully")
                return True
            if 400 <= resp.status_code < 500 and resp.status_code not in (408, 429):
                logger.warning(f"Permanent callback failure with status {resp.status_code}")
                return True
            logger.warning(
                f"Callback failed with status {resp.status_code} (attempt {attempt + 1})"
            )
        except Exception as e:
            logger.warning(f"Callback error: {e} (attempt {attempt + 1})")
        if attempt + 1 < CALLBACK_MAX_ATTEMPTS:
            await asyncio.sleep(delays[min(attempt, len(delays) - 1)])
    return False


async def _send_callback_with_reservation(reservation: CallbackReservation, *args):
    await send_callback(*args, callback_reservation=reservation)


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
    callback_reservation: CallbackReservation | None = None,
    *,
    status_code: int,
    error: dict,
    count_failure: bool,
) -> JSONResponse:
    """Build the shared failure response: error body, callback, optional failure counter."""
    if count_failure:
        RUNTIME_INVOCATIONS_TOTAL.labels(function=FUNCTION_NAME, success="false").inc()
    if callback_url and callback_reservation is not None:
        if not _schedule_callback(
            background_tasks,
            callback_url,
            execution_id,
            trace_id,
            {"success": False, "output": None, "error": error},
            x_dispatch_attempt,
            callback_reservation=callback_reservation,
        ):
            callback_reservation.release()
    elif callback_reservation is not None:
        callback_reservation.release()
    return JSONResponse(status_code=status_code, content={"error": error})


def _schedule_callback(
    background_tasks: BackgroundTasks,  # NOSONAR (python:S1172): kept so tests prove callbacks never use it
    callback_url: str,
    execution_id: str,
    trace_id: str | None,
    result: dict,
    dispatch_attempt: str | None = None,
    *,
    callback_reservation: CallbackReservation | None = None,
) -> bool:
    reservation = callback_reservation
    owns_reservation = reservation is None
    if reservation is None:
        try:
            reservation = _runtime_work.reserve_callback(MAX_CALLBACK_BYTES)
        except HandlerAdmissionError:
            logger.warning("Rejecting callback because runtime callback capacity is unavailable")
            return False
    try:
        serialized_result = _encode_json_bounded(result, MAX_CALLBACK_BYTES)
        reservation.resize(len(serialized_result))
    except (PayloadTooLargeError, HandlerAdmissionError):
        logger.warning("Rejecting callback because its serialized payload exceeds capacity")
        if owns_reservation:
            reservation.release()
        return False
    try:
        _runtime_work.start_reserved_callback_task(
            reservation,
            _send_callback_with_reservation,
            reservation,
            callback_url,
            execution_id,
            trace_id,
            serialized_result,
            dispatch_attempt,
        )
    except HandlerAdmissionError:
        if owns_reservation:
            reservation.release()
        return False
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
    returns the canonical bounded HTTP 500 handler error.

    Cold-start detection: the first invocation appends ``X-Cold-Start: true``
    and ``X-Init-Duration-Ms`` to the response headers and increments the
    ``runtime_cold_start_total`` Prometheus counter.

    When a callback URL is available (from the ``X-Callback-Url`` header or
    the ``CALLBACK_URL`` environment variable), the result is forwarded
    asynchronously via :func:`send_callback` as runtime-manager-owned work.

    :param request: The incoming FastAPI request object.
    :type request: fastapi.Request
    :param background_tasks: FastAPI-injected compatibility parameter; callback
        ownership belongs to :class:`RuntimeWorkManager`.
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
    execution_id, trace_id, handler = _resolve_invocation(x_execution_id, x_trace_id)
    callback_url = x_callback_url or CALLBACK_URL
    is_cold_start = _consume_cold_start()

    start = time.perf_counter()
    RUNTIME_IN_FLIGHT.labels(function=FUNCTION_NAME).inc()
    handler_execution = None
    callback_reservation = None
    try:
        try:
            callback_reservation = _reserve_invocation_callback(callback_url)
        except HandlerAdmissionError as error:
            return _callback_admission_rejection(error)

        try:
            payload = await _read_bounded_json(request)
        except (BodyReadTimeoutError, PayloadTooLargeError) as error:
            if callback_reservation is not None:
                callback_reservation.release()
            callback_reservation = None
            return _body_rejection(error)
        except json.JSONDecodeError:
            reservation = callback_reservation
            callback_reservation = None
            return _fail_response(
                background_tasks, callback_url, execution_id, trace_id, x_dispatch_attempt,
                callback_reservation=reservation,
                status_code=400, count_failure=False,
                error={"code": "INVALID_JSON", "message": "Request body must be valid JSON"},
            )
        input_data = _handler_input(payload)

        logger.info(f"Invoking handler for execution {execution_id}")

        try:
            handler_execution = _runtime_work.start_handler(handler, input_data)
        except HandlerAdmissionError as error:
            return _handler_admission_rejection(error)
        output = await handler_execution.wait(HANDLER_TIMEOUT_SECONDS)

        reservation = callback_reservation
        callback_reservation = None
        return _build_success_response(
            output, execution_id, is_cold_start, background_tasks, callback_url, trace_id,
            x_dispatch_attempt, reservation
        )
    except asyncio.TimeoutError:
        _runtime_work.record_wait_timeout()
        reservation = callback_reservation
        callback_reservation = None
        return _fail_response(
            background_tasks, callback_url, execution_id, trace_id, x_dispatch_attempt,
            callback_reservation=reservation,
            status_code=504, count_failure=True,
            error={"code": "HANDLER_TIMEOUT", "message": "Handler exceeded configured timeout"},
        )
    except asyncio.CancelledError:
        _cancel_invocation(
            handler_execution, callback_reservation, callback_url, execution_id, trace_id,
            x_dispatch_attempt,
        )
        callback_reservation = None
        raise
    except Exception:
        logger.error("Handler failed in execution %s", execution_id)
        RUNTIME_INVOCATIONS_TOTAL.labels(function=FUNCTION_NAME, success="false").inc()
        error = {"code": "HANDLER_ERROR", "message": "Handler failed"}
        reservation = callback_reservation
        callback_reservation = None
        return _fail_response(
            background_tasks, callback_url, execution_id, trace_id, x_dispatch_attempt,
            callback_reservation=reservation,
            status_code=500, error=error, count_failure=False,
        )
    finally:
        if callback_reservation is not None:
            callback_reservation.release()
        elapsed = time.perf_counter() - start
        RUNTIME_INVOCATION_DURATION_SECONDS.labels(function=FUNCTION_NAME).observe(elapsed)
        RUNTIME_IN_FLIGHT.labels(function=FUNCTION_NAME).dec()


def _resolve_invocation(x_execution_id: str | None, x_trace_id: str | None):
    """Return the execution id, trace id and handler of a request, or raise its HTTP error."""
    execution_id = x_execution_id or DEFAULT_EXECUTION_ID
    trace_id = x_trace_id or DEFAULT_TRACE_ID or None
    if not execution_id:
        raise HTTPException(status_code=400, detail="Execution ID required")
    context.set_context(execution_id, trace_id)
    handler = decorator.get_registered_handler()
    if not handler:
        logger.error("No handler registered")
        raise HTTPException(status_code=500, detail="No function registered with @nanofaas_function")
    return execution_id, trace_id, handler


def _reserve_invocation_callback(callback_url: str | None) -> CallbackReservation | None:
    if not callback_url:
        return None
    return _runtime_work.reserve_callback(MAX_CALLBACK_BYTES, preserve_on_stop=True)


def _handler_input(payload):
    """Return the handler input and publish the forwarded request headers to the context."""
    # Keep the Python runtime aligned with the Java InvocationRequest contract.
    # Handlers receive the input field rather than the transport envelope.
    if not isinstance(payload, dict):
        context.set_headers(None)
        return payload
    # Request headers are captured and filtered by the control plane and ride in the
    # body; this runtime's own HTTP headers belong to the control-plane hop.
    context.set_headers(payload.get("headers"))
    return payload.get("input")


def _cancel_invocation(
    handler_execution,
    callback_reservation: CallbackReservation | None,
    callback_url: str,
    execution_id: str,
    trace_id: str | None,
    dispatch_attempt: str | None,
) -> None:
    if handler_execution is not None:
        handler_execution.request_cancel()
    if callback_reservation is not None:
        _start_cancellation_callback(
            callback_reservation, callback_url, execution_id, trace_id, dispatch_attempt
        )


def _error_response(status_code: int, code: str, message: str, *, retryable: bool = False) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={"error": {"code": code, "message": message}},
        headers={"Retry-After": "1"} if retryable else None,
    )


def _callback_admission_rejection(error: HandlerAdmissionError) -> JSONResponse:
    if str(error) == "stopping":
        return _error_response(503, "RUNTIME_STOPPING", "Runtime is stopping", retryable=True)
    return _error_response(
        429, "RUNTIME_CALLBACK_SATURATED", "Runtime callback capacity exhausted", retryable=True
    )


def _handler_admission_rejection(error: HandlerAdmissionError) -> JSONResponse:
    if str(error) == "stopping":
        return _error_response(503, "RUNTIME_STOPPING", "Runtime is stopping", retryable=True)
    RUNTIME_HANDLER_SATURATION_TOTAL.labels(function=FUNCTION_NAME).inc()
    return _error_response(
        429, "RUNTIME_HANDLER_SATURATED", "Runtime handler capacity exhausted", retryable=True
    )


def _body_rejection(error: Exception) -> JSONResponse:
    if isinstance(error, BodyReadTimeoutError):
        return _error_response(408, "RUNTIME_BODY_READ_TIMEOUT", "Runtime request body read timed out")
    return _error_response(413, "RUNTIME_INPUT_TOO_LARGE", "Runtime input exceeds configured byte limit")


def _start_cancellation_callback(
    callback_reservation: CallbackReservation,
    callback_url: str,
    execution_id: str,
    trace_id: str | None,
    dispatch_attempt: str | None,
) -> None:
    """Hand the reserved callback an INVOCATION_CANCELLED result, or release it."""
    cancellation_result = {
        "success": False,
        "output": None,
        "error": {"code": "INVOCATION_CANCELLED", "message": "Invocation cancelled"},
    }
    try:
        serialized_result = _encode_json_bounded(cancellation_result, MAX_CALLBACK_BYTES)
        callback_reservation.resize(len(serialized_result))
        _runtime_work.start_reserved_callback_task(
            callback_reservation,
            _send_callback_with_reservation,
            callback_reservation,
            callback_url,
            execution_id,
            trace_id,
            serialized_result,
            dispatch_attempt,
        )
    except (PayloadTooLargeError, HandlerAdmissionError):
        callback_reservation.release()


def _build_success_response(
    output, execution_id, is_cold_start, background_tasks, callback_url, trace_id,
    x_dispatch_attempt, callback_reservation=None
) -> JSONResponse:
    """Build the success response, honouring an optional HandlerResponse envelope."""
    response_status = 200
    response_headers = _build_cold_start_headers(is_cold_start)
    response_body = output
    callback_status_code = None
    callback_headers = None
    callback_encoding = None

    if isinstance(output, HandlerResponse):
        if not (200 <= output.status_code <= 599):
            return _invalid_status_response(
                output, execution_id, background_tasks, callback_url, trace_id,
                x_dispatch_attempt, callback_reservation
            )
        response_status = output.status_code
        allowed = _filter_response_headers(output.headers, execution_id)
        response_headers = {**response_headers, **allowed, "X-NanoFaaS-Function-Status": "true"}
        if output.encoding:
            response_headers["X-NanoFaaS-Encoding"] = output.encoding
        response_body = output.output
        callback_status_code = output.status_code
        callback_headers = allowed
        callback_encoding = output.encoding

    try:
        _encode_json_bounded(response_body, MAX_OUTPUT_BYTES)
    except (TypeError, ValueError):  # PayloadTooLargeError is a ValueError
        error = {
            "code": "RUNTIME_OUTPUT_TOO_LARGE",
            "message": "Runtime output exceeds configured byte limit",
        }
        return _fail_response(
            background_tasks, callback_url, execution_id, trace_id, x_dispatch_attempt,
            callback_reservation=callback_reservation,
            status_code=500, error=error, count_failure=True,
        )

    result = {"success": True, "output": response_body, "error": None}
    if callback_status_code is not None:
        result["statusCode"] = callback_status_code
        result["headers"] = callback_headers
        result["encoding"] = callback_encoding

    if callback_url and callback_reservation is not None:
        if not _schedule_callback(
            background_tasks, callback_url, execution_id, trace_id, result, x_dispatch_attempt
            , callback_reservation=callback_reservation
        ):
            error = {
                "code": "RUNTIME_OUTPUT_TOO_LARGE",
                "message": "Runtime output exceeds configured byte limit",
            }
            return _fail_response(
                background_tasks, callback_url, execution_id, trace_id, x_dispatch_attempt,
                callback_reservation=callback_reservation,
                status_code=500, error=error, count_failure=True,
            )
    elif callback_reservation is not None:
        callback_reservation.release()

    RUNTIME_INVOCATIONS_TOTAL.labels(function=FUNCTION_NAME, success="true").inc()

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


def _invalid_status_response(
    output, execution_id, background_tasks, callback_url, trace_id, x_dispatch_attempt,
    callback_reservation=None
) -> JSONResponse:
    logger.warning(
        f"Handler returned invalid statusCode {output.status_code} for execution {execution_id}, treating as platform error"
    )
    RUNTIME_INVOCATIONS_TOTAL.labels(function=FUNCTION_NAME, success="false").inc()
    if callback_url and callback_reservation is not None:
        if not _schedule_callback(
            background_tasks, callback_url, execution_id, trace_id,
            {"success": False, "output": None,
             "error": {"code": "OUTPUT_SERIALIZATION_ERROR",
                       "message": f"Handler returned invalid statusCode: {output.status_code}"}},
            x_dispatch_attempt,
            callback_reservation=callback_reservation,
        ):
            callback_reservation.release()
    elif callback_reservation is not None:
        callback_reservation.release()
    return JSONResponse(status_code=500, content={
        "error": f"Handler returned invalid statusCode: {output.status_code}"})


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
