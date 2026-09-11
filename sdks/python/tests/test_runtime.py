import os
import sys
import asyncio
import importlib
import json
import threading
from unittest.mock import patch, MagicMock

import pytest
import requests
from fastapi.testclient import TestClient

# Add src to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '../src'))

import nanofaas.runtime.app as _app
from nanofaas.runtime.app import app
from nanofaas.sdk import context, decorator
from nanofaas.sdk.response import HandlerResponse

@pytest.fixture
def client():
    return TestClient(app)


def test_handler_timeout_environment_uses_milliseconds():
    original = os.environ.get("NANOFAAS_HANDLER_TIMEOUT")
    try:
        os.environ["NANOFAAS_HANDLER_TIMEOUT"] = "250"
        assert importlib.reload(_app).HANDLER_TIMEOUT_SECONDS == 0.25
    finally:
        if original is None:
            os.environ.pop("NANOFAAS_HANDLER_TIMEOUT", None)
        else:
            os.environ["NANOFAAS_HANDLER_TIMEOUT"] = original
        importlib.reload(_app)


@pytest.mark.parametrize(
    ("setting", "value"),
    [
        ("NANOFAAS_HANDLER_TIMEOUT", "0"),
        ("NANOFAAS_HANDLER_TIMEOUT", "nan"),
        ("NANOFAAS_HANDLER_TIMEOUT", "inf"),
        ("NANOFAAS_SHUTDOWN_TIMEOUT", "0"),
        ("NANOFAAS_SHUTDOWN_TIMEOUT", "nan"),
        ("NANOFAAS_SHUTDOWN_TIMEOUT", "inf"),
    ],
)
def test_wait_limits_require_finite_positive_milliseconds(monkeypatch, setting, value):
    with monkeypatch.context() as environment:
        environment.setenv(setting, value)
        with pytest.raises(ValueError, match=f"{setting} must be finite and positive"):
            importlib.reload(_app)
    importlib.reload(_app)

def test_health(client):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}

def test_metrics_exposed_and_increments(client):
    @decorator.nanofaas_function
    def mock_handler(input_data):
        return {"ok": True}

    # Trigger at least one invocation
    response = client.post("/invoke",
                         json={"input": "hello"},
                         headers={"X-Execution-Id": "exec-metrics"})
    assert response.status_code == 200

    metrics = client.get("/metrics")
    assert metrics.status_code == 200
    body = metrics.text
    assert "runtime_invocations_total" in body
    assert 'function="' in body

def test_invoke_without_handler(client):
    # Reset decorator registry for this test
    with patch('nanofaas.sdk.decorator._registered_handler', None):
        response = client.post("/invoke", 
                             json={"input": "test"},
                             headers={"X-Execution-Id": "exec-1"})
        assert response.status_code == 500
        assert "No function registered" in response.json()["detail"]

def test_invoke_success(client):
    @decorator.nanofaas_function
    def mock_handler(input_data):
        return {"echo": input_data}
    
    response = client.post("/invoke", 
                         json={"input": "hello"},
                         headers={"X-Execution-Id": "exec-123", "X-Trace-Id": "trace-456"})
    
    assert response.status_code == 200
    assert response.json() == {"echo": "hello"}

def test_invoke_missing_execution_id(client):
    @decorator.nanofaas_function
    def mock_handler(input_data):
        return {}
        
    response = client.post("/invoke", json={"input": "test"})
    assert response.status_code == 400
    assert "Execution ID required" in response.json()["detail"]

@patch("requests.post")
def test_callback_triggered(mock_post, client):
    @decorator.nanofaas_function
    def mock_handler(input_data):
        return "done"
    
    # We use TestClient which runs background tasks synchronously by default if not specified otherwise
    # or we might need to wait if it was async. FastAPI TestClient runs them.
    
    response = client.post("/invoke", 
                         json={"input": "test"},
                         headers={
                             "X-Execution-Id": "exec-cb",
                             "X-Callback-Url": "http://control-plane/callbacks"
                         })
    
    assert response.status_code == 200
    
    # Verify callback
    mock_post.assert_called()
    call_args = mock_post.call_args
    assert "http://control-plane/callbacks/exec-cb:complete" in call_args[0][0]
    assert call_args[1]["json"]["success"] is True
    assert call_args[1]["json"]["output"] == "done"

@patch("nanofaas.runtime.app.asyncio.to_thread")
@patch("requests.post")
def test_callback_uses_asyncio_to_thread(mock_post, mock_to_thread, client):
    """Legacy regression: callbacks now bypass asyncio's shared default executor."""
    callback_threads = []

    def capture_callback_thread(*_args, **_kwargs):
        callback_threads.append(threading.current_thread().name)
        return MagicMock(status_code=200)

    mock_post.side_effect = capture_callback_thread

    @decorator.nanofaas_function
    def mock_handler(input_data):
        return "done"

    response = client.post(
        "/invoke",
        json={"input": "test"},
        headers={
            "X-Execution-Id": "exec-async-cb",
            "X-Callback-Url": "http://cp/callbacks",
        },
    )
    assert response.status_code == 200
    mock_to_thread.assert_not_called()
    assert callback_threads
    assert callback_threads[0].startswith("nanofaas-callback")

def test_cold_start_counted_exactly_once_under_concurrency(client, monkeypatch):
    """Only the very first request must be flagged as a cold start."""
    # Reset the cold-start flag so this test is independent of execution order.
    monkeypatch.setattr(_app, "_first_invocation", True)

    @decorator.nanofaas_function
    def mock_handler(input_data):
        return {"ok": True}

    cold_starts = []
    errors = []

    def invoke(idx):
        try:
            r = client.post(
                "/invoke",
                json={"input": idx},
                headers={"X-Execution-Id": f"exec-conc-{idx}"},
            )
            if r.headers.get("X-Cold-Start") == "true":
                cold_starts.append(idx)
        except Exception as e:
            errors.append(e)

    threads = [threading.Thread(target=invoke, args=(i,)) for i in range(10)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert not errors, f"Invocation errors: {errors}"
    # Exactly one request should be marked as a cold start.
    assert len(cold_starts) == 1, \
        f"Expected exactly 1 cold-start, got {len(cold_starts)}: {cold_starts}"


def test_malformed_json_returns_invalid_json(client):
    @decorator.nanofaas_function
    def mock_handler(input_data):
        return input_data

    response = client.post(
        "/invoke",
        content="{",
        headers={"Content-Type": "application/json", "X-Execution-Id": "exec-json"},
    )

    assert response.status_code == 400
    assert response.json() == {
        "error": {"code": "INVALID_JSON", "message": "Request body must be valid JSON"}
    }


def test_handler_timeout_returns_504(client, monkeypatch):
    monkeypatch.setattr(_app, "HANDLER_TIMEOUT_SECONDS", 0.01, raising=False)

    @decorator.nanofaas_function
    async def slow_handler(_input_data):
        await asyncio.sleep(0.1)

    response = client.post(
        "/invoke",
        json={"input": "slow"},
        headers={"X-Execution-Id": "exec-timeout"},
    )

    assert response.status_code == 504
    assert response.json()["error"]["code"] == "HANDLER_TIMEOUT"


@patch("requests.post")
def test_trace_environment_fallback_and_dispatch_attempt_are_forwarded(mock_post, client, monkeypatch):
    mock_post.return_value.status_code = 204
    monkeypatch.setattr(_app, "DEFAULT_TRACE_ID", "trace-env", raising=False)

    @decorator.nanofaas_function
    def mock_handler(input_data):
        return input_data

    response = client.post(
        "/invoke",
        json={"input": "ok"},
        headers={
            "X-Execution-Id": "exec-context",
            "X-Dispatch-Attempt": "4",
            "X-Callback-Url": "http://control-plane/callbacks",
        },
    )

    assert response.status_code == 200
    headers = mock_post.call_args.kwargs["headers"]
    assert headers["X-Trace-Id"] == "trace-env"
    assert headers["X-Dispatch-Attempt"] == "4"


def test_callback_retries_retryable_status_but_not_permanent_4xx():
    retryable = MagicMock(side_effect=[
        MagicMock(status_code=429),
        MagicMock(status_code=204),
    ])
    with patch("requests.post", retryable):
        asyncio.run(_app.send_callback("http://cp/callbacks", "exec-retry", None, {}))
    assert retryable.call_count == 2

    permanent = MagicMock(return_value=MagicMock(status_code=400))
    with patch("requests.post", permanent):
        asyncio.run(_app.send_callback("http://cp/callbacks", "exec-400", None, {}))
    assert permanent.call_count == 1


@patch("requests.post")
def test_callback_submission_is_bounded(mock_post, client, monkeypatch):
    slots = threading.BoundedSemaphore(1)
    slots.acquire()
    monkeypatch.setattr(_app, "_callback_slots", slots, raising=False)

    @decorator.nanofaas_function
    def mock_handler(input_data):
        return input_data

    response = client.post(
        "/invoke",
        json={"input": "ok"},
        headers={
            "X-Execution-Id": "exec-full",
            "X-Callback-Url": "http://control-plane/callbacks",
        },
    )

    assert response.status_code == 200
    mock_post.assert_not_called()
    slots.release()


def test_invoke_handler_returns_handler_response_uses_its_status_and_headers(client):
    @decorator.nanofaas_function
    def mock_handler(input_data):
        return HandlerResponse({"error": "not found"}, 404, {"Content-Type": "application/json"})

    response = client.post("/invoke", json={"input": {}}, headers={"X-Execution-Id": "ex-1"})
    assert response.status_code == 404
    assert response.headers["content-type"].startswith("application/json")
    assert response.headers["x-nanofaas-function-status"] == "true"


def test_invoke_handler_returns_handler_response_drops_disallowed_headers(client):
    @decorator.nanofaas_function
    def mock_handler(input_data):
        return HandlerResponse({"ok": True}, 200, {"X-Execution-Id": "spoof", "X-Custom": "nope"})

    response = client.post("/invoke", json={"input": {}}, headers={"X-Execution-Id": "ex-2"})
    assert response.headers.get("x-custom") is None
    assert response.headers.get("x-execution-id") != "spoof"


def test_invoke_handler_response_dropped_headers_log_warn_but_still_succeed(client, caplog):
    @decorator.nanofaas_function
    def mock_handler(input_data):
        return HandlerResponse({"ok": True}, 200, {"X-Execution-Id": "spoof", "X-Custom": "nope"})

    with caplog.at_level("WARNING", logger="nanofaas.runtime.app"):
        response = client.post("/invoke", json={"input": {}}, headers={"X-Execution-Id": "ex-2b"})

    # Dropping disallowed headers must never fail the invocation.
    assert response.status_code == 200
    assert response.headers["x-nanofaas-function-status"] == "true"

    warnings = [r.message for r in caplog.records if r.levelname == "WARNING"]
    assert len(warnings) == 1, f"expected exactly one WARN, got {warnings}"
    assert "Dropped response header(s)" in warnings[0]
    assert "X-Execution-Id" in warnings[0] and "X-Custom" in warnings[0]
    assert "ex-2b" in warnings[0]


def test_filter_response_headers_dedupes_case_insensitively():
    from nanofaas.runtime.app import _filter_response_headers

    filtered = _filter_response_headers({"Content-Type": "application/pdf", "content-type": "text/plain"})

    assert filtered == {"Content-Type": "application/pdf"}, "first occurrence wins, original casing kept"


def test_filter_response_headers_preserves_original_casing():
    from nanofaas.runtime.app import _filter_response_headers

    assert _filter_response_headers({"Content-Type": "application/pdf"}) == {"Content-Type": "application/pdf"}


def test_invoke_handler_response_deduped_header_named_in_warn_alongside_disallowed_one(client, caplog):
    @decorator.nanofaas_function
    def mock_handler(input_data):
        return HandlerResponse(
            {"ok": True},
            200,
            {"Content-Type": "application/pdf", "content-type": "text/plain", "X-Custom": "nope"},
        )

    with caplog.at_level("WARNING", logger="nanofaas.runtime.app"):
        response = client.post("/invoke", json={"input": {}}, headers={"X-Execution-Id": "ex-2c"})

    assert response.status_code == 200

    warnings = [r.message for r in caplog.records if r.levelname == "WARNING"]
    assert len(warnings) == 1, f"expected exactly one WARN, got {warnings}"
    assert "content-type" in warnings[0], "WARN must name the deduplicated header, not just the disallowed one"
    assert "X-Custom" in warnings[0]
    assert "ex-2c" in warnings[0]


def test_invoke_handler_returns_handler_response_invalid_status_falls_back_to_500(client):
    @decorator.nanofaas_function
    def mock_handler(input_data):
        return HandlerResponse({"x": 1}, 999)

    response = client.post("/invoke", json={"input": {}}, headers={"X-Execution-Id": "ex-3"})
    assert response.status_code == 500
    assert "x-nanofaas-function-status" not in response.headers


def test_invoke_handler_returns_plain_value_behaves_as_today(client):
    @decorator.nanofaas_function
    def mock_handler(input_data):
        return {"roman": "XLII"}

    response = client.post("/invoke", json={"input": {}}, headers={"X-Execution-Id": "ex-4"})
    assert response.status_code == 200
    assert "x-nanofaas-function-status" not in response.headers


def test_invoke_exposes_request_headers_from_body_to_handler(client):
    seen = {}

    @decorator.nanofaas_function
    def mock_handler(input_data):
        seen.update(context.get_headers())
        return {"ok": True}

    client.post(
        "/invoke",
        json={"input": {}, "headers": {"authorization": "Bearer x"}},
        headers={"X-Execution-Id": "ex-5"},
    )
    assert seen.get("authorization") == "Bearer x"


def test_invoke_ignores_own_http_headers_for_handler_context(client):
    # The runtime's inbound HTTP headers are the control plane's, not the caller's —
    # reading them here is the bug Task 6 (Java side) exists to prevent. This test
    # pins that the Python runtime only trusts payload["headers"].
    seen = {}

    @decorator.nanofaas_function
    def mock_handler(input_data):
        seen.update(context.get_headers())
        return {"ok": True}

    client.post("/invoke", json={"input": {}}, headers={
        "X-Execution-Id": "ex-6", "Authorization": "Bearer leaked"})
    assert seen == {}


def test_invoke_envelope_body_is_output_verbatim(client):
    # Java returns the normalized output bare; Python must not apply its usual
    # {"result": ...} wrapping in the envelope path.
    @decorator.nanofaas_function
    def mock_handler(input_data):
        return HandlerResponse("hello", 200, {"Content-Type": "text/plain"})

    response = client.post("/invoke", json={"input": {}}, headers={"X-Execution-Id": "ex-7"})
    assert response.json() == "hello"


def test_invoke_handler_response_with_encoding_emits_encoding_header(client):
    @decorator.nanofaas_function
    def mock_handler(input_data):
        return HandlerResponse("aGVsbG8=", 200, {"Content-Type": "application/octet-stream"}, encoding="base64")

    response = client.post("/invoke", json={"input": {}}, headers={"X-Execution-Id": "ex-enc-1"})
    assert response.headers["x-nanofaas-encoding"] == "base64"


def test_invoke_handler_response_without_encoding_omits_encoding_header(client):
    @decorator.nanofaas_function
    def mock_handler(input_data):
        return HandlerResponse({"error": "not found"}, 404, {"Content-Type": "application/json"})

    response = client.post("/invoke", json={"input": {}}, headers={"X-Execution-Id": "ex-enc-2"})
    assert "x-nanofaas-encoding" not in response.headers


def test_invoke_plain_value_never_emits_encoding_header(client):
    @decorator.nanofaas_function
    def mock_handler(input_data):
        return {"roman": "XLII"}

    response = client.post("/invoke", json={"input": {}}, headers={"X-Execution-Id": "ex-enc-3"})
    assert "x-nanofaas-encoding" not in response.headers


def test_invoke_handler_cannot_spoof_encoding_header_through_its_own_headers_map(client):
    # X-NanoFaaS-Encoding is not in _ALLOWED_RESPONSE_HEADERS, so a handler stuffing it into
    # its own headers dict must never leak it through — only the dedicated `encoding` field can.
    @decorator.nanofaas_function
    def mock_handler(input_data):
        return HandlerResponse("body", 200, {"X-NanoFaaS-Encoding": "spoofed"})

    response = client.post("/invoke", json={"input": {}}, headers={"X-Execution-Id": "ex-enc-4"})
    assert response.headers.get("x-nanofaas-encoding") != "spoofed"
    assert "x-nanofaas-encoding" not in response.headers


@patch("requests.post")
def test_invoke_envelope_callback_uses_camelcase_wire_keys(mock_post, client):
    mock_post.return_value.status_code = 204

    @decorator.nanofaas_function
    def mock_handler(input_data):
        return HandlerResponse({"ok": True}, 201, {"Content-Type": "application/json"}, encoding="base64")

    response = client.post(
        "/invoke",
        json={"input": {}},
        headers={"X-Execution-Id": "ex-8", "X-Callback-Url": "http://control-plane/callbacks"},
    )
    assert response.status_code == 201
    callback_body = mock_post.call_args.kwargs["json"]
    assert callback_body["statusCode"] == 201
    assert callback_body["headers"] == {"Content-Type": "application/json"}
    assert callback_body["encoding"] == "base64"
    assert "status_code" not in callback_body


def _reload_runtime_with_limits(monkeypatch, **limits):
    for name, value in limits.items():
        monkeypatch.setenv(name, str(value))
    return importlib.reload(_app)


class _RequestBody:
    def __init__(self, input_data):
        self.input_data = input_data

    async def json(self):
        return {"input": self.input_data}


class _LoopHarness:
    def __init__(self):
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)

    def invoke(self, runtime, execution_id, input_data=None):
        return self.loop.run_until_complete(
            runtime.invoke(
                _RequestBody(input_data),
                runtime.BackgroundTasks(),
                x_execution_id=execution_id,
            )
        )

    def close(self):
        self.loop.run_until_complete(self.loop.shutdown_default_executor(timeout=1.0))
        self.loop.close()


class _ShutdownAttemptCondition:
    def __init__(self, condition, shutdown_attempted):
        self._condition = condition
        self._shutdown_attempted = shutdown_attempted

    def __enter__(self):
        if threading.current_thread().name == "runtime-shutdown-test":
            self._shutdown_attempted.set()
        return self._condition.__enter__()

    def __exit__(self, exc_type, exc_value, traceback):
        return self._condition.__exit__(exc_type, exc_value, traceback)

    def notify_all(self):
        self._condition.notify_all()


def test_shutdown_cannot_miss_handler_between_submit_and_registration(monkeypatch):
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_MAX_CONCURRENT_HANDLERS=1,
    )
    handler_started = threading.Event()
    release_handler = threading.Event()
    shutdown_attempted = threading.Event()
    execution_holder = []
    start_errors = []
    shutdown_reports = []

    original_submit = runtime._runtime_work._handler_executor.submit

    def blocked_handler(_input_data):
        handler_started.set()
        assert release_handler.wait(1.0), "test handler release was not signalled"

    def submit_before_registration(*args, **kwargs):
        work = original_submit(*args, **kwargs)
        assert shutdown_attempted.wait(1.0), "shutdown did not attempt manager lock"
        return work

    def start_handler():
        try:
            execution_holder.append(runtime._runtime_work.start_handler(blocked_handler, None))
        except BaseException as error:
            start_errors.append(error)

    def stop_runtime():
        shutdown_reports.append(asyncio.run(runtime._runtime_work.shutdown(0.02)))

    runtime._runtime_work._lock = _ShutdownAttemptCondition(
        runtime._runtime_work._lock,
        shutdown_attempted,
    )
    monkeypatch.setattr(runtime._runtime_work._handler_executor, "submit", submit_before_registration)

    starter = threading.Thread(target=start_handler, daemon=True)
    stopper = threading.Thread(target=stop_runtime, name="runtime-shutdown-test", daemon=True)
    try:
        starter.start()
        assert handler_started.wait(1.0)
        stopper.start()
        stopper.join(1.0)
        starter.join(1.0)

        assert not stopper.is_alive()
        assert not starter.is_alive()
        assert start_errors == []
        assert len(execution_holder) == 1
        assert shutdown_reports[0].drained is False
        assert shutdown_reports[0].active_handlers == 1
    finally:
        release_handler.set()
        starter.join(1.0)
        stopper.join(1.0)
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        importlib.reload(_app)


def test_sync_timeout_keeps_physical_handler_admitted_until_event_release(monkeypatch):
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_HANDLER_TIMEOUT=20,
        NANOFAAS_MAX_CONCURRENT_HANDLERS=4,
    )
    release = threading.Event()
    first_started = threading.Event()
    all_finished = threading.Event()
    lock = threading.Lock()
    calls = active = maximum_active = 0

    @decorator.nanofaas_function
    def blocked_handler(_input_data):
        nonlocal calls, active, maximum_active
        with lock:
            calls += 1
            active += 1
            maximum_active = max(maximum_active, active)
            first_started.set()
        assert release.wait(1.0), "test release was not signalled"
        with lock:
            active -= 1
            if active == 0:
                all_finished.set()
        return {"ok": True}

    try:
        loop_harness = _LoopHarness()
        first = loop_harness.invoke(runtime, "sync-timeout-0", 0)
        assert first_started.wait(1.0)
        assert first.status_code == 504
        timed_out = [first] + [
            loop_harness.invoke(runtime, f"sync-timeout-{index}", index)
            for index in range(1, 4)
        ]
        assert [response.status_code for response in timed_out] == [504] * 4

        refused = [
            loop_harness.invoke(runtime, f"sync-timeout-{index}", index)
            for index in range(4, 12)
        ]

        assert [response.status_code for response in refused] == [429] * 8
        assert all(
            json.loads(response.body)["error"]["code"] == "RUNTIME_HANDLER_SATURATED"
            for response in refused
        )
        assert all(response.headers["retry-after"] == "1" for response in refused)
        assert calls == 4
        assert maximum_active == 4
        assert runtime.health() == {"status": "ok"}

        metrics_response = runtime.metrics()
        metrics_text = metrics_response.body.decode()
        assert 'runtime_active_handlers{function="unknown"} 4.0' in metrics_text
        assert "runtime_handler_wait_timeouts_total" in metrics_text

        release.set()
        assert all_finished.wait(0.5)
        assert calls == 4
        loop_harness.close()
    finally:
        release.set()
        if "loop_harness" in locals() and not loop_harness.loop.is_closed():
            loop_harness.close()
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        importlib.reload(_app)


def test_async_timeout_returns_while_delayed_cancellation_stays_physically_active(monkeypatch):
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_HANDLER_TIMEOUT=20,
        NANOFAAS_MAX_CONCURRENT_HANDLERS=1,
    )
    started = threading.Event()
    cancellation_seen = threading.Event()
    release_ready = threading.Event()
    finished = threading.Event()
    release_handle = {}

    @decorator.nanofaas_function
    async def delayed_cancel_handler(_input_data):
        started.set()
        try:
            await asyncio.Future()
        except asyncio.CancelledError:
            cancellation_seen.set()
            loop = asyncio.get_running_loop()
            release_handle["loop"] = loop
            release_handle["future"] = loop.create_future()
            release_ready.set()
            await release_handle["future"]
            finished.set()
            return {"late": True}

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    invocation = loop.create_task(
        runtime.invoke(
            _RequestBody(None),
            runtime.BackgroundTasks(),
            x_execution_id="async-timeout",
        )
    )
    try:
        response = loop.run_until_complete(
            asyncio.wait_for(asyncio.shield(invocation), timeout=0.2)
        )
        assert started.wait(0)
        assert cancellation_seen.wait(0)
        assert release_ready.wait(0)
        assert response.status_code == 504

        snapshot = runtime._runtime_work.snapshot()
        assert snapshot.active_handlers == 1
        assert snapshot.timed_out_waits >= 1
        release_handle["future"].set_result(None)
        loop.run_until_complete(asyncio.sleep(0))
        assert finished.wait(0)
    finally:
        if release_ready.is_set() and not release_handle["future"].done():
            release_handle["future"].set_result(None)
        if not invocation.done():
            loop.run_until_complete(asyncio.wait_for(invocation, timeout=0.5))
        loop.run_until_complete(loop.shutdown_default_executor(timeout=1.0))
        loop.close()
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        importlib.reload(_app)


def test_lifespan_shutdown_is_bounded_and_reports_non_cooperative_handler(monkeypatch, caplog):
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_HANDLER_TIMEOUT=20,
        NANOFAAS_SHUTDOWN_TIMEOUT=50,
        NANOFAAS_MAX_CONCURRENT_HANDLERS=1,
    )
    started = threading.Event()
    release = threading.Event()
    finished = threading.Event()

    @decorator.nanofaas_function
    def non_cooperative_handler(_input_data):
        started.set()
        assert release.wait(1.0), "test release was not signalled"
        finished.set()
        return {"ok": True}

    try:
        loop_harness = _LoopHarness()
        response = loop_harness.invoke(runtime, "shutdown-timeout")
        assert started.wait(1.0)
        assert response.status_code == 504

        async def stop_runtime():
            with caplog.at_level("WARNING", logger="nanofaas.runtime.app"):
                async with runtime.lifespan(runtime.app):
                    pass

        asyncio.run(stop_runtime())

        report = getattr(runtime.app.state, "runtime_shutdown_report", None)
        assert report is not None
        assert report.drained is False
        assert report.active_handlers == 1
    finally:
        release.set()
        assert finished.wait(0.5)
        if "loop_harness" in locals() and not loop_harness.loop.is_closed():
            loop_harness.close()
        importlib.reload(_app)


def test_lifespan_shutdown_drains_cooperative_async_handler(monkeypatch):
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_SHUTDOWN_TIMEOUT=200,
        NANOFAAS_MAX_CONCURRENT_HANDLERS=1,
    )
    started = asyncio.Event()
    cancelled = asyncio.Event()

    async def cooperative_handler(_input_data):
        started.set()
        try:
            await asyncio.Future()
        except asyncio.CancelledError:
            cancelled.set()
            raise

    async def run_and_stop():
        async with runtime.lifespan(runtime.app):
            runtime._runtime_work.start_handler(cooperative_handler, None)
            await asyncio.wait_for(started.wait(), timeout=0.2)

    try:
        asyncio.run(run_and_stop())
        report = runtime.app.state.runtime_shutdown_report
        assert cancelled.is_set()
        assert report.drained is True
        assert report.active_handlers == 0
    finally:
        importlib.reload(_app)


def test_delayed_async_cancellation_keeps_handler_capacity_owned(monkeypatch):
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_MAX_CONCURRENT_HANDLERS=1,
    )

    async def exercise():
        started = asyncio.Event()
        cancellation_seen = asyncio.Event()
        release = asyncio.Event()

        async def delayed_handler(_input_data):
            started.set()
            try:
                await asyncio.Future()
            except asyncio.CancelledError:
                cancellation_seen.set()
                await release.wait()

        execution = runtime._runtime_work.start_handler(delayed_handler, None)
        await asyncio.wait_for(started.wait(), timeout=0.2)
        with pytest.raises(asyncio.TimeoutError):
            await execution.wait(0.01)
        await asyncio.wait_for(cancellation_seen.wait(), timeout=0.2)

        with pytest.raises(runtime.HandlerAdmissionError, match="saturated"):
            runtime._runtime_work.start_handler(delayed_handler, None)
        assert runtime._runtime_work.snapshot().active_handlers == 1

        release.set()
        await asyncio.wait_for(execution.work, timeout=0.2)
        assert runtime._runtime_work.snapshot().active_handlers == 0
        assert (await runtime._runtime_work.shutdown(0.2)).drained is True

    try:
        asyncio.run(exercise())
    finally:
        importlib.reload(_app)


def test_callback_worker_progresses_while_handler_capacity_is_saturated(monkeypatch):
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_MAX_CONCURRENT_HANDLERS=1,
        NANOFAAS_CALLBACK_WORKERS=1,
        NANOFAAS_MAX_PENDING_CALLBACKS=1,
    )
    handler_started = threading.Event()
    release_handler = threading.Event()
    callback_threads = []

    def blocked_handler(_input_data):
        handler_started.set()
        assert release_handler.wait(1.0), "test handler release was not signalled"

    def callback_call():
        callback_threads.append(threading.current_thread().name)
        return MagicMock(status_code=204)

    async def exercise():
        execution = runtime._runtime_work.start_handler(blocked_handler, None)
        assert handler_started.wait(1.0)
        response = await asyncio.wait_for(
            runtime._runtime_work.run_callback_call(callback_call),
            timeout=0.2,
        )
        assert response.status_code == 204
        assert callback_threads[0].startswith("nanofaas-callback")
        assert runtime._runtime_work.snapshot().active_handlers == 1

        release_handler.set()
        await asyncio.wait_for(asyncio.wrap_future(execution.work), timeout=0.2)
        assert (await runtime._runtime_work.shutdown(0.2)).drained is True

    try:
        asyncio.run(exercise())
    finally:
        release_handler.set()
        importlib.reload(_app)


def test_callback_admission_uses_configured_limit_before_background_submission(monkeypatch):
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_MAX_PENDING_CALLBACKS=2,
        NANOFAAS_CALLBACK_WORKERS=2,
    )
    background_tasks = runtime.BackgroundTasks()
    callback_args = ("http://callback.invalid", "callback-limit", None, {}, None)

    try:
        admitted = [
            runtime._schedule_callback(background_tasks, *callback_args)
            for _index in range(3)
        ]
        assert admitted == [True, True, False]
        with patch("requests.post", return_value=MagicMock(status_code=204)):
            asyncio.run(background_tasks())
        assert runtime._runtime_work.snapshot().pending_callbacks == 0
    finally:
        if runtime._runtime_work.snapshot().pending_callbacks:
            for _index in range(sum(admitted)):
                runtime._runtime_work.release_callback()
                runtime._callback_slots.release()
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        importlib.reload(_app)


def test_simultaneous_callbacks_use_owned_workers_and_health_keeps_progressing(monkeypatch):
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_MAX_CONCURRENT_HANDLERS=1,
        NANOFAAS_MAX_PENDING_CALLBACKS=2,
        NANOFAAS_CALLBACK_WORKERS=2,
    )
    callbacks_started = threading.Event()
    release_callbacks = threading.Event()
    callback_threads = []
    lock = threading.Lock()

    def blocking_post(*_args, **_kwargs):
        with lock:
            callback_threads.append(threading.current_thread().name)
            if len(callback_threads) == 2:
                callbacks_started.set()
        assert release_callbacks.wait(1.0), "test callback release was not signalled"
        return MagicMock(status_code=204)

    async def exercise_callbacks():
        await asyncio.wait_for(
            asyncio.gather(
                *(
                    runtime.send_callback(
                        "http://callback.invalid", f"callback-worker-{index}", None, {}
                    )
                    for index in range(2)
                )
            ),
            timeout=1.5,
        )

    callback_runner_errors = []

    def run_callbacks():
        try:
            asyncio.run(exercise_callbacks())
        except BaseException as error:
            callback_runner_errors.append(error)

    try:
        with patch("requests.post", side_effect=blocking_post):
            callback_runner = threading.Thread(target=run_callbacks, daemon=True)
            callback_runner.start()
            assert callbacks_started.wait(1.0)

            assert runtime.health() == {"status": "ok"}
            snapshot = runtime._runtime_work.snapshot()
            assert snapshot.active_callback_workers == 2
            assert all(name.startswith("nanofaas-callback") for name in callback_threads)

            bounded_report = asyncio.run(runtime._runtime_work.shutdown(0.02))
            assert bounded_report.drained is False
            assert bounded_report.active_callback_workers == 2

            release_callbacks.set()
            callback_runner.join(1.0)
            assert not callback_runner.is_alive()
            assert callback_runner_errors == []
        assert runtime._runtime_work.snapshot().active_callback_workers == 0
        assert asyncio.run(runtime._runtime_work.shutdown(0.5)).drained is True
    finally:
        release_callbacks.set()
        if "callback_runner" in locals():
            callback_runner.join(1.0)
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        importlib.reload(_app)
