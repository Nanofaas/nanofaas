import os
import sys
import asyncio
import importlib
import threading
from unittest.mock import patch, MagicMock

import pytest
import requests
from fastapi.testclient import TestClient

# Add src to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '../src'))

import nanofaas.runtime.app as _app
from nanofaas.runtime.app import app
from nanofaas.sdk import decorator

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
def test_callback_uses_asyncio_to_thread(mock_to_thread, client):
    """send_callback must offload requests.post to a thread, not call it directly."""
    mock_response = MagicMock()
    mock_response.status_code = 200

    async def _to_thread(*args, **kwargs):
        if args[0] is requests.post:
            return mock_response
        return args[0](*args[1:], **kwargs)

    mock_to_thread.side_effect = _to_thread

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
    mock_to_thread.assert_called()
    assert mock_to_thread.call_args[0][0] is requests.post

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
