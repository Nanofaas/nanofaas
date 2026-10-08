import os
import sys
import asyncio
import importlib
import json
import threading
from unittest.mock import patch, MagicMock, AsyncMock

import pytest
import httpx
from sdks.python.tests.asgi_test_client import ASGITestClient

# Add src to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '../src'))

import nanofaas.runtime.app as _app
from nanofaas.runtime.app import app
from nanofaas.sdk import context, decorator
from nanofaas.sdk.response import HandlerResponse

@pytest.fixture
def client():
    return ASGITestClient(app, _app)


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
        ("NANOFAAS_BODY_READ_TIMEOUT", "0"),
        ("NANOFAAS_BODY_READ_TIMEOUT", "nan"),
        ("NANOFAAS_BODY_READ_TIMEOUT", "inf"),
        ("NANOFAAS_CALLBACK_ATTEMPT_TIMEOUT", "0"),
        ("NANOFAAS_CALLBACK_ATTEMPT_TIMEOUT", "nan"),
        ("NANOFAAS_CALLBACK_ATTEMPT_TIMEOUT", "inf"),
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

@patch("nanofaas.runtime.callback_transport.post_callback")
def test_callback_triggered(mock_post, client):
    mock_post.return_value = 204
    @decorator.nanofaas_function
    def mock_handler(input_data):
        return "done"
    
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
    callback_body = json.loads(call_args[1]["body"])
    assert callback_body["success"] is True
    assert callback_body["output"] == "done"

@patch("nanofaas.runtime.app.asyncio.to_thread")
@patch("nanofaas.runtime.callback_transport.post_callback")
def test_callback_uses_asyncio_to_thread(mock_post, mock_to_thread):
    """Legacy regression: callbacks now bypass asyncio's shared default executor."""
    callback_threads = []
    callback_finished = threading.Event()

    async def capture_callback_thread(*_args, **_kwargs):
        callback_threads.append(threading.current_thread().name)
        callback_finished.set()
        return 200

    mock_post.side_effect = capture_callback_thread

    @decorator.nanofaas_function
    def mock_handler(input_data):
        return "done"

    async def exercise():
        response = await _app.invoke(
            _RequestBody("test"),
            _app.BackgroundTasks(),
            x_execution_id="exec-async-cb",
            x_callback_url="http://cp/callbacks",
        )
        await asyncio.wait_for(
            asyncio.gather(*tuple(_app._runtime_work._callback_tasks)), timeout=0.2
        )
        return response

    response = asyncio.run(exercise())
    assert response.status_code == 200
    assert callback_finished.wait(0), "callback worker did not complete"
    mock_to_thread.assert_not_called()
    assert callback_threads
    assert callback_threads[0] == threading.current_thread().name

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
    assert response.json() == {
        "error": {
            "code": "HANDLER_TIMEOUT",
            "message": "Handler exceeded configured timeout",
        }
    }
    assert "retry-after" not in response.headers


@patch("nanofaas.runtime.callback_transport.post_callback")
def test_trace_environment_fallback_and_dispatch_attempt_are_forwarded(mock_post, client, monkeypatch):
    mock_post.return_value = 204
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
    retryable = AsyncMock(side_effect=[
        429,
        204,
    ])
    with patch("nanofaas.runtime.callback_transport.post_callback", retryable):
        asyncio.run(_app.send_callback("http://cp/callbacks", "exec-retry", None, {}))
    assert retryable.call_count == 2

    permanent = AsyncMock(return_value=400)
    with patch("nanofaas.runtime.callback_transport.post_callback", permanent):
        asyncio.run(_app.send_callback("http://cp/callbacks", "exec-400", None, {}))
    assert permanent.call_count == 1


@patch("nanofaas.runtime.callback_transport.post_callback")
def test_callback_submission_is_bounded(mock_post, client, monkeypatch):
    reservations = [
        _app._runtime_work.reserve_callback(_app.MAX_CALLBACK_BYTES)
        for _index in range(
            min(
                _app.MAX_PENDING_CALLBACKS,
                _app.MAX_PENDING_CALLBACK_BYTES // _app.MAX_CALLBACK_BYTES,
            )
        )
    ]

    @decorator.nanofaas_function
    def mock_handler(input_data):
        return input_data

    try:
        response = client.post(
            "/invoke",
            json={"input": "ok"},
            headers={
                "X-Execution-Id": "exec-full",
                "X-Callback-Url": "http://control-plane/callbacks",
            },
        )

        assert response.status_code == 429
        assert response.json()["error"]["code"] == "RUNTIME_CALLBACK_SATURATED"
        mock_post.assert_not_called()
    finally:
        for reservation in reservations:
            reservation.release()


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


@patch("nanofaas.runtime.callback_transport.post_callback")
def test_invoke_envelope_callback_uses_camelcase_wire_keys(mock_post, client):
    callback_finished = threading.Event()

    async def successful_post(*_args, **_kwargs):
        callback_finished.set()
        return 204

    mock_post.side_effect = successful_post

    @decorator.nanofaas_function
    def mock_handler(input_data):
        return HandlerResponse({"ok": True}, 201, {"Content-Type": "application/json"}, encoding="base64")

    response = client.post(
        "/invoke",
        json={"input": {}},
        headers={"X-Execution-Id": "ex-8", "X-Callback-Url": "http://control-plane/callbacks"},
    )
    assert response.status_code == 201
    assert callback_finished.wait(0.2), "callback worker did not complete"
    callback_body = json.loads(mock_post.call_args.kwargs["body"])
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

    @decorator.nanofaas_function
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

    async def callback_call():
        callback_threads.append(threading.current_thread().name)
        return 204

    async def exercise():
        execution = runtime._runtime_work.start_handler(blocked_handler, None)
        assert handler_started.wait(1.0)
        response = await asyncio.wait_for(
            runtime._runtime_work.run_callback_call(callback_call),
            timeout=0.2,
        )
        assert response == 204
        assert callback_threads[0] == threading.current_thread().name
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

    async def exercise():
        admitted = [
            runtime._schedule_callback(background_tasks, *callback_args)
            for _index in range(3)
        ]
        assert admitted == [True, True, False]
        assert background_tasks.tasks == []
        await asyncio.wait_for(
            asyncio.gather(*tuple(runtime._runtime_work._callback_tasks)), timeout=0.2
        )

    try:
        with patch("nanofaas.runtime.callback_transport.post_callback", return_value=204):
            asyncio.run(exercise())
        assert runtime._runtime_work.snapshot().pending_callbacks == 0
    finally:
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        importlib.reload(_app)


def test_simultaneous_callbacks_use_owned_workers_and_health_keeps_progressing(monkeypatch):
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_MAX_CONCURRENT_HANDLERS=1,
        NANOFAAS_MAX_PENDING_CALLBACKS=2,
        NANOFAAS_CALLBACK_WORKERS=2,
    )
    async def exercise():
        callbacks_started = asyncio.Event()
        release_callbacks = asyncio.Event()
        started = 0

        async def blocking_post(*_args, **_kwargs):
            nonlocal started
            started += 1
            if started == 2:
                callbacks_started.set()
            try:
                await asyncio.Event().wait()
            finally:
                await release_callbacks.wait()
            return 204

        with patch("nanofaas.runtime.callback_transport.post_callback", side_effect=blocking_post):
            callbacks = [asyncio.create_task(runtime.send_callback(
                "http://callback.invalid", f"callback-worker-{index}", None, {}
            )) for index in range(2)]
            try:
                await asyncio.wait_for(callbacks_started.wait(), 0.5)
                assert runtime.health() == {"status": "ok"}
                snapshot = runtime._runtime_work.snapshot()
                assert snapshot.active_callback_workers == 2
                assert snapshot.pending_callbacks == 2
                assert snapshot.pending_callback_bytes > 0
                report = await runtime._runtime_work.shutdown(0.02)
                assert report.drained is False
                assert report.active_callback_workers == 2
            finally:
                release_callbacks.set()
                await asyncio.gather(*callbacks, return_exceptions=True)
            snapshot = runtime._runtime_work.snapshot()
            assert snapshot.active_callback_workers == 0
            assert snapshot.pending_callbacks == 0
            assert snapshot.pending_callback_bytes == 0
            assert (await runtime._runtime_work.shutdown(0.5)).drained is True

    try:
        asyncio.run(asyncio.wait_for(exercise(), 1.0))
    finally:
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        importlib.reload(_app)


@pytest.mark.parametrize(
    "setting",
    [
        "NANOFAAS_MAX_INPUT_BYTES",
        "NANOFAAS_MAX_OUTPUT_BYTES",
        "NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES",
        "NANOFAAS_MAX_PENDING_CALLBACK_BYTES",
    ],
)
def test_payload_byte_limits_require_positive_integers(monkeypatch, setting):
    with monkeypatch.context() as environment:
        environment.setenv(setting, "0")
        with pytest.raises(ValueError, match=f"{setting} must be positive"):
            importlib.reload(_app)
    importlib.reload(_app)


def test_callback_max_attempts_requires_positive_integer(monkeypatch):
    with monkeypatch.context() as environment:
        environment.setenv("NANOFAAS_CALLBACK_MAX_ATTEMPTS", "0")
        with pytest.raises(
            ValueError, match="NANOFAAS_CALLBACK_MAX_ATTEMPTS must be positive"
        ):
            importlib.reload(_app)
    importlib.reload(_app)


@patch("nanofaas.runtime.callback_transport.post_callback")
def test_callback_attempt_timeout_and_retry_count_use_finite_configuration(
    mock_post, monkeypatch
):
    mock_post.return_value = 503
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_CALLBACK_ATTEMPT_TIMEOUT=41,
        NANOFAAS_CALLBACK_MAX_ATTEMPTS=2,
    )
    try:
        asyncio.run(runtime.send_callback("http://callback.invalid", "configured", None, {}))
        assert mock_post.call_count == 2
        assert all(call.kwargs["timeout_seconds"] == 0.041 for call in mock_post.call_args_list)
    finally:
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        importlib.reload(_app)


def test_single_callback_limit_must_fit_pending_callback_bytes(monkeypatch):
    with monkeypatch.context() as environment:
        environment.setenv("NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES", "65")
        environment.setenv("NANOFAAS_MAX_PENDING_CALLBACK_BYTES", "64")
        with pytest.raises(
            ValueError,
            match=(
                "NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES must not exceed "
                "NANOFAAS_MAX_PENDING_CALLBACK_BYTES"
            ),
        ):
            importlib.reload(_app)
    importlib.reload(_app)


def test_payload_limit_defaults_and_callback_setting_match_other_runtimes(monkeypatch):
    with monkeypatch.context() as environment:
        for setting in (
            "NANOFAAS_MAX_INPUT_BYTES",
            "NANOFAAS_MAX_OUTPUT_BYTES",
            "NANOFAAS_MAX_CALLBACK_BYTES",
            "NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES",
            "NANOFAAS_MAX_PENDING_CALLBACK_BYTES",
        ):
            environment.delenv(setting, raising=False)
        runtime = importlib.reload(_app)
        assert runtime.MAX_INPUT_BYTES == 1024 * 1024
        assert runtime.MAX_OUTPUT_BYTES == 1024 * 1024
        assert runtime.MAX_CALLBACK_BYTES == 2 * 1024 * 1024
        assert runtime.MAX_PENDING_CALLBACK_BYTES == 16 * 1024 * 1024

        environment.setenv("NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES", "77")
        environment.setenv("NANOFAAS_MAX_PENDING_CALLBACK_BYTES", "77")
        runtime = importlib.reload(_app)
        assert runtime.MAX_CALLBACK_BYTES == 77
    importlib.reload(_app)


def test_callback_count_and_bytes_are_reserved_before_handler_admission(monkeypatch):
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_MAX_INPUT_BYTES=1024,
        NANOFAAS_MAX_OUTPUT_BYTES=1024,
        NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES=64,
        NANOFAAS_MAX_PENDING_CALLBACK_BYTES=64,
        NANOFAAS_MAX_PENDING_CALLBACKS=1,
    )
    handler_calls = 0
    reservation = runtime._runtime_work.reserve_callback(64)

    @decorator.nanofaas_function
    def must_not_run(_input_data):
        nonlocal handler_calls
        handler_calls += 1

    try:
        response = asyncio.run(
            runtime.invoke(
                _RequestBody("ok"),
                runtime.BackgroundTasks(),
                x_execution_id="callback-saturated",
                x_callback_url="http://callback.invalid",
            )
        )

        assert response.status_code == 429
        assert json.loads(response.body) == {
            "error": {
                "code": "RUNTIME_CALLBACK_SATURATED",
                "message": "Runtime callback capacity exhausted",
            }
        }
        assert response.headers["retry-after"] == "1"
        assert handler_calls == 0
        snapshot = runtime._runtime_work.snapshot()
        assert snapshot.pending_callbacks == 1
        assert snapshot.pending_callback_bytes == 64
    finally:
        reservation.release()
        assert runtime._runtime_work.snapshot().pending_callbacks == 0
        assert runtime._runtime_work.snapshot().pending_callback_bytes == 0
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        importlib.reload(_app)


@patch("nanofaas.runtime.callback_transport.post_callback")
def test_direct_runtime_rejects_oversized_input_before_handler_or_callback(mock_post, monkeypatch):
    runtime = _reload_runtime_with_limits(monkeypatch, NANOFAAS_MAX_INPUT_BYTES=32)
    handler_calls = 0

    @decorator.nanofaas_function
    def must_not_run(_input_data):
        nonlocal handler_calls
        handler_calls += 1

    try:
        with ASGITestClient(runtime.app, runtime) as runtime_client:
            response = runtime_client.post(
                "/invoke",
                json={"input": "x" * 64},
                headers={
                    "X-Execution-Id": "input-too-large",
                    "X-Callback-Url": "http://callback.invalid",
                },
            )

        assert response.status_code == 413
        assert response.json() == {
            "error": {
                "code": "RUNTIME_INPUT_TOO_LARGE",
                "message": "Runtime input exceeds configured byte limit",
            }
        }
        assert "retry-after" not in response.headers
        assert handler_calls == 0
        mock_post.assert_not_called()
    finally:
        importlib.reload(_app)


@patch("nanofaas.runtime.callback_transport.post_callback")
def test_direct_runtime_rejects_oversized_output_and_delivers_canonical_callback(
    mock_post, monkeypatch
):
    mock_post.return_value = 204
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_MAX_OUTPUT_BYTES=8,
        NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES=512,
        NANOFAAS_MAX_PENDING_CALLBACK_BYTES=512,
    )

    @decorator.nanofaas_function
    def oversized_output(_input_data):
        return "0123456789"

    try:
        with ASGITestClient(runtime.app, runtime) as runtime_client:
            response = runtime_client.post(
                "/invoke",
                json={"input": "ok"},
                headers={
                    "X-Execution-Id": "output-too-large",
                    "X-Callback-Url": "http://callback.invalid",
                },
            )

        expected_error = {
            "code": "RUNTIME_OUTPUT_TOO_LARGE",
            "message": "Runtime output exceeds configured byte limit",
        }
        assert response.status_code == 500
        assert response.json() == {"error": expected_error}
        assert "retry-after" not in response.headers
        callback = json.loads(mock_post.call_args.kwargs["body"])
        assert callback == {"success": False, "output": None, "error": expected_error}
        assert runtime._runtime_work.snapshot().pending_callbacks == 0
        assert runtime._runtime_work.snapshot().pending_callback_bytes == 0
    finally:
        importlib.reload(_app)


def test_handler_error_uses_canonical_wire_body_without_retry_after(monkeypatch):
    runtime = _reload_runtime_with_limits(monkeypatch)

    @decorator.nanofaas_function
    def failed_handler(_input_data):
        raise RuntimeError("Handler failed")

    try:
        with ASGITestClient(runtime.app, runtime) as runtime_client:
            response = runtime_client.post(
                "/invoke",
                json={"input": "ok"},
                headers={"X-Execution-Id": "handler-error"},
            )

        assert response.status_code == 500
        assert response.json() == {
            "error": {"code": "HANDLER_ERROR", "message": "Handler failed"}
        }
        assert "retry-after" not in response.headers
    finally:
        importlib.reload(_app)


def test_handler_payload_exception_is_not_misclassified_as_input_rejection(monkeypatch):
    runtime = _reload_runtime_with_limits(monkeypatch)

    @decorator.nanofaas_function
    def failed_handler(_input_data):
        raise runtime.PayloadTooLargeError("handler-owned failure")

    try:
        with ASGITestClient(runtime.app, runtime) as runtime_client:
            response = runtime_client.post(
                "/invoke",
                json={"input": "ok"},
                headers={"X-Execution-Id": "handler-payload-error"},
            )
        assert response.status_code == 500
        assert response.json() == {
            "error": {"code": "HANDLER_ERROR", "message": "Handler failed"}
        }
    finally:
        importlib.reload(_app)


def test_pending_callback_byte_cap_saturates_before_count_cap(monkeypatch):
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_MAX_PENDING_CALLBACKS=2,
        NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES=64,
        NANOFAAS_MAX_PENDING_CALLBACK_BYTES=64,
    )
    reservation = runtime._runtime_work.reserve_callback(64)
    try:
        with pytest.raises(runtime.HandlerAdmissionError, match="callback saturated"):
            runtime._runtime_work.reserve_callback(1)
        snapshot = runtime._runtime_work.snapshot()
        assert snapshot.pending_callbacks == 1
        assert snapshot.pending_callback_bytes == 64
    finally:
        reservation.release()
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        importlib.reload(_app)


def test_pending_callback_byte_metric_tracks_reservation(monkeypatch):
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES=64,
        NANOFAAS_MAX_PENDING_CALLBACK_BYTES=64,
    )
    reservation = runtime._runtime_work.reserve_callback(64)
    try:
        metrics_text = runtime.metrics().body.decode()
        assert 'runtime_pending_callback_bytes{function="unknown"} 64.0' in metrics_text
    finally:
        reservation.release()
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        importlib.reload(_app)


def test_callback_serialization_rejection_releases_self_owned_reservation(monkeypatch):
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES=64,
        NANOFAAS_MAX_PENDING_CALLBACK_BYTES=64,
    )
    try:
        accepted = runtime._schedule_callback(
            runtime.BackgroundTasks(),
            "http://callback.invalid",
            "serialization-rejected",
            None,
            {"success": True, "output": "x" * 128, "error": None},
        )
        assert accepted is False
        snapshot = runtime._runtime_work.snapshot()
        assert snapshot.pending_callbacks == 0
        assert snapshot.pending_callback_bytes == 0
    finally:
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        importlib.reload(_app)


def test_oversized_string_is_rejected_before_json_encoder_creates_a_copy(monkeypatch):
    def must_not_encode(_encoder, _value):
        raise AssertionError("oversized scalar reached JSON encoding")

    monkeypatch.setattr(json.JSONEncoder, "iterencode", must_not_encode)
    with pytest.raises(_app.PayloadTooLargeError):
        _app._encode_json_bounded("x" * 65, 64)


@patch("nanofaas.runtime.callback_transport.post_callback")
def test_single_callback_cap_converts_success_to_output_too_large(mock_post, monkeypatch):
    mock_post.return_value = 204
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_MAX_OUTPUT_BYTES=128,
        NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES=140,
        NANOFAAS_MAX_PENDING_CALLBACK_BYTES=140,
    )

    @decorator.nanofaas_function
    def callback_oversized_output(_input_data):
        return "x" * 110

    try:
        success_counter = runtime.RUNTIME_INVOCATIONS_TOTAL.labels(
            function=runtime.FUNCTION_NAME, success="true"
        )
        failure_counter = runtime.RUNTIME_INVOCATIONS_TOTAL.labels(
            function=runtime.FUNCTION_NAME, success="false"
        )
        success_before = success_counter._value.get()
        failure_before = failure_counter._value.get()
        with ASGITestClient(runtime.app, runtime) as runtime_client:
            response = runtime_client.post(
                "/invoke",
                json={"input": "ok"},
                headers={
                    "X-Execution-Id": "callback-too-large",
                    "X-Callback-Url": "http://callback.invalid",
                },
            )

        assert response.status_code == 500
        assert response.json()["error"]["code"] == "RUNTIME_OUTPUT_TOO_LARGE"
        callback = json.loads(mock_post.call_args.kwargs["body"])
        assert callback["error"]["code"] == "RUNTIME_OUTPUT_TOO_LARGE"
        assert success_counter._value.get() == success_before
        assert failure_counter._value.get() == failure_before + 1
        assert runtime._runtime_work.snapshot().pending_callback_bytes == 0
    finally:
        importlib.reload(_app)


@patch("nanofaas.runtime.callback_transport.post_callback", side_effect=httpx.ConnectError("unreachable"))
def test_callback_delivery_error_releases_count_and_bytes(mock_post, monkeypatch):
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES=512,
        NANOFAAS_MAX_PENDING_CALLBACK_BYTES=512,
    )

    @decorator.nanofaas_function
    def successful_handler(_input_data):
        return "ok"

    try:
        with ASGITestClient(runtime.app, runtime) as runtime_client:
            response = runtime_client.post(
                "/invoke",
                json={"input": "ok"},
                headers={
                    "X-Execution-Id": "callback-unreachable",
                    "X-Callback-Url": "http://callback.invalid",
                },
            )

        assert response.status_code == 200
        assert mock_post.call_count == 3
        snapshot = runtime._runtime_work.snapshot()
        assert snapshot.pending_callbacks == 0
        assert snapshot.pending_callback_bytes == 0
    finally:
        importlib.reload(_app)


def test_shutdown_releases_reserved_callback_that_never_started(monkeypatch):
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES=64,
        NANOFAAS_MAX_PENDING_CALLBACK_BYTES=64,
    )
    runtime._runtime_work.reserve_callback(64)

    try:
        report = asyncio.run(runtime._runtime_work.shutdown(0.2))
        assert report.drained is True
        assert report.pending_callbacks == 0
        assert report.pending_callback_bytes == 0

        restarted = importlib.reload(_app)

        @decorator.nanofaas_function
        def restarted_handler(input_data):
            return input_data

        response = asyncio.run(
            restarted.invoke(
                _RequestBody("ok"),
                restarted.BackgroundTasks(),
                x_execution_id="after-restart",
            )
        )
        assert response.status_code == 200
        assert json.loads(response.body) == {"result": "ok"}
        asyncio.run(restarted._runtime_work.shutdown(0.2))
    finally:
        importlib.reload(_app)


def test_handler_saturation_releases_pre_handler_callback_reservation(monkeypatch):
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_MAX_CONCURRENT_HANDLERS=1,
        NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES=256,
        NANOFAAS_MAX_PENDING_CALLBACK_BYTES=256,
    )
    handler_started = threading.Event()
    release_handler = threading.Event()

    @decorator.nanofaas_function
    def blocked_handler(_input_data):
        handler_started.set()
        assert release_handler.wait(1.0), "test handler release was not signalled"

    execution = runtime._runtime_work.start_handler(blocked_handler, None)
    try:
        assert handler_started.wait(1.0)
        response = asyncio.run(
            runtime.invoke(
                _RequestBody("ok"),
                runtime.BackgroundTasks(),
                x_execution_id="handler-saturated",
                x_callback_url="http://callback.invalid",
            )
        )
        assert response.status_code == 429
        assert json.loads(response.body) == {
            "error": {
                "code": "RUNTIME_HANDLER_SATURATED",
                "message": "Runtime handler capacity exhausted",
            }
        }
        assert response.headers["retry-after"] == "1"
        snapshot = runtime._runtime_work.snapshot()
        assert snapshot.active_handlers == 1
        assert snapshot.pending_callbacks == 0
        assert snapshot.pending_callback_bytes == 0
    finally:
        release_handler.set()
        async def await_handler_exit():
            await asyncio.wait_for(asyncio.wrap_future(execution.work), timeout=0.2)

        asyncio.run(await_handler_exit())
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        importlib.reload(_app)


def test_content_length_rejection_does_not_read_request_stream(monkeypatch):
    runtime = _reload_runtime_with_limits(monkeypatch, NANOFAAS_MAX_INPUT_BYTES=32)
    handler_calls = 0

    class OversizedRequest:
        headers = {"content-length": "33"}

        async def stream(self):
            raise AssertionError("oversized body stream must not be consumed")
            yield b""

    @decorator.nanofaas_function
    def must_not_run(_input_data):
        nonlocal handler_calls
        handler_calls += 1

    try:
        response = asyncio.run(
            runtime.invoke(
                OversizedRequest(),
                runtime.BackgroundTasks(),
                x_execution_id="content-length-too-large",
            )
        )
        assert response.status_code == 413
        assert json.loads(response.body)["error"]["code"] == "RUNTIME_INPUT_TOO_LARGE"
        assert handler_calls == 0
    finally:
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        importlib.reload(_app)


def test_request_body_read_timeout_is_finite_and_does_not_start_handler(monkeypatch):
    runtime = _reload_runtime_with_limits(monkeypatch, NANOFAAS_BODY_READ_TIMEOUT=20)
    handler_calls = 0

    class StalledRequest:
        headers = {}

        async def stream(self):
            yield b'{"input":'
            await asyncio.Event().wait()

    @decorator.nanofaas_function
    def must_not_run(_input_data):
        nonlocal handler_calls
        handler_calls += 1

    try:
        response = asyncio.run(
            runtime.invoke(
                StalledRequest(),
                runtime.BackgroundTasks(),
                x_execution_id="body-timeout",
            )
        )
        assert response.status_code == 408
        assert json.loads(response.body) == {
            "error": {
                "code": "RUNTIME_BODY_READ_TIMEOUT",
                "message": "Runtime request body read timed out",
            }
        }
        assert "retry-after" not in response.headers
        assert handler_calls == 0
    finally:
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        importlib.reload(_app)


def test_direct_invoke_after_stop_returns_canonical_retryable_503(monkeypatch):
    runtime = _reload_runtime_with_limits(monkeypatch)

    @decorator.nanofaas_function
    def must_not_run(_input_data):
        raise AssertionError("stopped runtime must reject before handler")

    try:
        asyncio.run(runtime._runtime_work.shutdown(0.2))
        response = asyncio.run(
            runtime.invoke(
                _RequestBody("ok"),
                runtime.BackgroundTasks(),
                x_execution_id="stopped",
                x_callback_url="http://callback.invalid",
            )
        )
        assert response.status_code == 503
        assert json.loads(response.body) == {
            "error": {"code": "RUNTIME_STOPPING", "message": "Runtime is stopping"}
        }
        assert response.headers["retry-after"] == "1"
    finally:
        importlib.reload(_app)


@patch("nanofaas.runtime.callback_transport.post_callback")
def test_cancelled_direct_invocation_delivers_callback_and_releases_reservation(
    mock_post, monkeypatch
):
    mock_post.return_value = 204
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES=512,
        NANOFAAS_MAX_PENDING_CALLBACK_BYTES=512,
    )

    async def exercise():
        started = asyncio.Event()
        cancelled = asyncio.Event()

        @decorator.nanofaas_function
        async def cancellable_handler(_input_data):
            started.set()
            try:
                await asyncio.Future()
            except asyncio.CancelledError:
                cancelled.set()
                raise

        invocation = asyncio.create_task(
            runtime.invoke(
                _RequestBody("ok"),
                runtime.BackgroundTasks(),
                x_execution_id="cancelled",
                x_trace_id="trace-cancelled",
                x_callback_url="http://callback.invalid",
                x_dispatch_attempt="3",
            )
        )
        await asyncio.wait_for(started.wait(), timeout=0.2)
        invocation.cancel()
        with pytest.raises(asyncio.CancelledError):
            await invocation
        await asyncio.wait_for(cancelled.wait(), timeout=0.2)
        owned_callbacks = tuple(runtime._runtime_work._callback_tasks)
        assert len(owned_callbacks) == 1
        await asyncio.wait_for(asyncio.gather(*owned_callbacks), timeout=0.2)

        callback = json.loads(mock_post.call_args.kwargs["body"])
        assert callback == {
            "success": False,
            "output": None,
            "error": {"code": "INVOCATION_CANCELLED", "message": "Invocation cancelled"},
        }
        assert mock_post.call_args.kwargs["headers"]["X-Dispatch-Attempt"] == "3"
        snapshot = runtime._runtime_work.snapshot()
        assert snapshot.pending_callbacks == 0
        assert snapshot.pending_callback_bytes == 0

    try:
        asyncio.run(exercise())
    finally:
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        importlib.reload(_app)


def test_cancelled_callback_wait_keeps_bytes_owned_until_http_worker_exits(monkeypatch):
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES=512,
        NANOFAAS_MAX_PENDING_CALLBACK_BYTES=512,
    )
    worker_started = threading.Event()
    worker_exited = threading.Event()
    release_worker = threading.Event()

    async def blocked_post(*_args, **_kwargs):
        worker_started.set()
        try:
            await asyncio.Event().wait()
        finally:
            assert await asyncio.to_thread(release_worker.wait, 1.0), "callback cleanup release was not signalled"
            worker_exited.set()

    async def exercise():
        reservation = runtime._runtime_work.reserve_callback(256)
        callback_task = asyncio.create_task(
            runtime.send_callback(
                "http://callback.invalid",
                "cancel-worker",
                None,
                {"success": True, "output": "ok", "error": None},
                callback_reservation=reservation,
            )
        )
        async with asyncio.timeout(0.2):
            while not worker_started.is_set():
                await asyncio.sleep(0)

        callback_task.cancel()
        await asyncio.sleep(0)

        snapshot = runtime._runtime_work.snapshot()
        assert snapshot.active_callback_workers == 1
        assert snapshot.pending_callbacks == 1
        assert snapshot.pending_callback_bytes > 0

        release_worker.set()
        with pytest.raises(asyncio.CancelledError):
            await asyncio.wait_for(callback_task, timeout=0.2)
        async with asyncio.timeout(0.2):
            while not worker_exited.is_set() or runtime._runtime_work.snapshot().pending_callbacks:
                await asyncio.sleep(0)
        snapshot = runtime._runtime_work.snapshot()
        assert snapshot.active_callback_workers == 0
        assert snapshot.pending_callbacks == 0
        assert snapshot.pending_callback_bytes == 0

    monkeypatch.setattr(runtime.callback_transport, "post_callback", blocked_post)
    try:
        asyncio.run(exercise())
    finally:
        release_worker.set()
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        importlib.reload(_app)


def test_shutdown_drains_terminal_callback_reserved_before_handler_start(monkeypatch):
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_MAX_CONCURRENT_HANDLERS=1,
        NANOFAAS_SHUTDOWN_TIMEOUT=500,
    )
    handler_started = threading.Event()
    release_handler = threading.Event()
    callback_delivered = threading.Event()

    @decorator.nanofaas_function
    def blocked_handler(_input_data):
        handler_started.set()
        assert release_handler.wait(1.0), "handler release was not signalled"
        return {"ok": True}

    async def successful_post(*_args, **_kwargs):
        callback_delivered.set()
        return 204

    async def exercise():
        background_tasks = runtime.BackgroundTasks()
        invocation = asyncio.create_task(
            runtime.invoke(
                _RequestBody("ok"),
                background_tasks,
                x_execution_id="shutdown-callback-race",
                x_callback_url="http://callback.invalid",
            )
        )
        async with asyncio.timeout(0.2):
            while not handler_started.is_set():
                await asyncio.sleep(0)

        shutdown = asyncio.create_task(runtime._runtime_work.shutdown(0.5))
        await asyncio.sleep(0)
        release_handler.set()

        response = await asyncio.wait_for(invocation, timeout=0.2)
        report = await asyncio.wait_for(shutdown, timeout=0.5)
        assert response.status_code == 200
        assert callback_delivered.wait(0), "accepted terminal callback was lost"
        assert background_tasks.tasks == []
        assert report.drained is True
        assert report.pending_callbacks == 0
        assert report.pending_callback_bytes == 0

    monkeypatch.setattr(runtime.callback_transport, "post_callback", successful_post)
    try:
        asyncio.run(exercise())
    finally:
        release_handler.set()
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        importlib.reload(_app)


def test_callback_final_exhaustion_increments_bounded_metric_once(monkeypatch):
    runtime = _reload_runtime_with_limits(monkeypatch, NANOFAAS_CALLBACK_MAX_ATTEMPTS=3)
    attempts = 0

    async def retryable_post(*_args, **_kwargs):
        nonlocal attempts
        attempts += 1
        return 503

    async def no_delay(_seconds):
        return None

    monkeypatch.setattr(runtime.callback_transport, "post_callback", retryable_post)
    monkeypatch.setattr(runtime.asyncio, "sleep", no_delay)
    try:
        before = runtime.RUNTIME_CALLBACK_DELIVERY_FAILURES_TOTAL.labels(
            function=runtime.FUNCTION_NAME
        )._value.get()
        asyncio.run(
            runtime.send_callback(
                "http://callback.invalid",
                "exhausted",
                None,
                {"success": True, "output": "ok", "error": None},
            )
        )
        after = runtime.RUNTIME_CALLBACK_DELIVERY_FAILURES_TOTAL.labels(
            function=runtime.FUNCTION_NAME
        )._value.get()
        assert attempts == 3
        assert after == before + 1
    finally:
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        importlib.reload(_app)


@patch("nanofaas.runtime.callback_transport.post_callback")
def test_handler_failure_response_and_callback_never_expose_exception_text(
    mock_post, client
):
    mock_post.return_value = 204

    class UnrenderableError(RuntimeError):
        def __str__(self):
            raise AssertionError("exception text must not be rendered")

    @decorator.nanofaas_function
    def failed_handler(_input_data):
        raise UnrenderableError()

    response = client.post(
        "/invoke",
        json={"input": "ok"},
        headers={
            "X-Execution-Id": "bounded-handler-error",
            "X-Callback-Url": "http://callback.invalid",
        },
    )

    expected_error = {"code": "HANDLER_ERROR", "message": "Handler failed"}
    assert response.status_code == 500
    assert response.json() == {"error": expected_error}
    callback = json.loads(mock_post.call_args.kwargs["body"])
    assert callback == {"success": False, "output": None, "error": expected_error}


def test_immediately_cancelled_owned_callback_releases_unstarted_reservation(monkeypatch):
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES=128,
        NANOFAAS_MAX_PENDING_CALLBACK_BYTES=128,
    )
    callback_entered = False

    async def callback_body(reservation):
        nonlocal callback_entered
        callback_entered = True
        reservation.release()

    async def exercise():
        reservation = runtime._runtime_work.reserve_callback(64)
        task = runtime._runtime_work.start_reserved_callback_task(
            reservation, callback_body, reservation
        )
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        await asyncio.sleep(0)

        assert callback_entered is False
        assert runtime._runtime_work.snapshot().pending_callbacks == 0
        assert runtime._runtime_work.snapshot().pending_callback_bytes == 0
        reservation.release()
        assert runtime._runtime_work.snapshot().pending_callbacks == 0

    try:
        asyncio.run(exercise())
    finally:
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        importlib.reload(_app)


def test_concurrent_future_bridge_does_not_cancel_source_work(monkeypatch):
    runtime = _reload_runtime_with_limits(monkeypatch)
    source = runtime.Future()

    async def exercise():
        waiter = asyncio.create_task(runtime._await_concurrent_future(source))
        await asyncio.sleep(0)
        waiter.cancel()
        with pytest.raises(asyncio.CancelledError):
            await waiter
        assert source.cancelled() is False

    try:
        asyncio.run(exercise())
    finally:
        if not source.done():
            source.set_result("finished")
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        importlib.reload(_app)


def test_immediate_handler_and_callback_futures_never_stall(monkeypatch):
    runtime = _reload_runtime_with_limits(monkeypatch)

    async def exercise():
        async with asyncio.timeout(1.0):
            for index in range(100):
                handler = runtime._runtime_work.start_handler(lambda value: value, index)
                assert await handler.wait(0.2) == index
                async def immediate_callback(value):
                    return value
                assert await runtime._runtime_work.run_callback_call(immediate_callback, index) == index

    try:
        asyncio.run(exercise())
    finally:
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        importlib.reload(_app)


def test_raw_callback_bytes_over_single_payload_cap_are_rejected(monkeypatch):
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES=64,
        NANOFAAS_MAX_PENDING_CALLBACK_BYTES=128,
    )
    post = AsyncMock(return_value=204)
    monkeypatch.setattr(runtime.callback_transport, "post_callback", post)

    async def exercise():
        with pytest.raises(runtime.PayloadTooLargeError):
            await runtime.send_callback(
                "http://callback.invalid", "raw-too-large", None, b"x" * 65
            )

    try:
        asyncio.run(exercise())
        post.assert_not_called()
        assert runtime._runtime_work.snapshot().pending_callbacks == 0
        assert runtime._runtime_work.snapshot().pending_callback_bytes == 0
    finally:
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        importlib.reload(_app)


def test_raw_callback_reservation_tracks_exact_serialized_bytes(monkeypatch):
    runtime = _reload_runtime_with_limits(
        monkeypatch,
        NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES=64,
        NANOFAAS_MAX_PENDING_CALLBACK_BYTES=128,
    )
    worker_started = threading.Event()
    release_worker = threading.Event()

    async def blocked_post(*_args, **_kwargs):
        worker_started.set()
        assert await asyncio.to_thread(release_worker.wait, 1.0), "raw callback worker release was not signalled"
        return 204

    async def exercise():
        reservation = runtime._runtime_work.reserve_callback(64)
        task = asyncio.create_task(
            runtime.send_callback(
                "http://callback.invalid",
                "raw-exact",
                None,
                b"x" * 17,
                callback_reservation=reservation,
            )
        )
        async with asyncio.timeout(0.2):
            while not worker_started.is_set():
                await asyncio.sleep(0)
        assert runtime._runtime_work.snapshot().pending_callback_bytes == 17
        release_worker.set()
        await asyncio.wait_for(task, timeout=0.2)
        assert runtime._runtime_work.snapshot().pending_callback_bytes == 0

    monkeypatch.setattr(runtime.callback_transport, "post_callback", blocked_post)
    try:
        asyncio.run(exercise())
    finally:
        release_worker.set()
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        importlib.reload(_app)
