import os
import sys
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
        return mock_response

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
