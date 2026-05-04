from __future__ import annotations

import json
from unittest.mock import MagicMock

import httpx
import pytest

from controlplane_tool.sut.sut_preflight import SutPreflight


# ---------------------------------------------------------------------------
# _request — httpx implementation
# ---------------------------------------------------------------------------

def test_request_get_returns_status_and_body(monkeypatch) -> None:
    mock_response = MagicMock(spec=httpx.Response)
    mock_response.status_code = 200
    mock_response.text = '["foo"]'
    monkeypatch.setattr("controlplane_tool.sut.sut_preflight.httpx.request", lambda *a, **kw: mock_response)
    preflight = SutPreflight(base_url="http://127.0.0.1:8080")
    status, body = preflight._request("GET", "/v1/functions")
    assert status == 200
    assert body == '["foo"]'


def test_request_post_sends_json_payload(monkeypatch) -> None:
    captured: list[dict] = []

    def _fake_request(method, url, **kwargs):
        captured.append({"method": method, "url": url, "kwargs": kwargs})
        mock = MagicMock(spec=httpx.Response)
        mock.status_code = 201
        mock.text = "{}"
        return mock

    monkeypatch.setattr("controlplane_tool.sut.sut_preflight.httpx.request", _fake_request)
    preflight = SutPreflight(base_url="http://127.0.0.1:8080")
    status, body = preflight._request("POST", "/v1/functions", {"name": "echo"})
    assert status == 201
    assert captured[0]["method"] == "POST"
    assert captured[0]["kwargs"]["json"] == {"name": "echo"}


def test_request_raises_runtime_error_on_connection_failure(monkeypatch) -> None:
    monkeypatch.setattr(
        "controlplane_tool.sut.sut_preflight.httpx.request",
        lambda *a, **kw: (_ for _ in ()).throw(httpx.RequestError("refused")),
    )
    preflight = SutPreflight(base_url="http://127.0.0.1:8080")
    with pytest.raises(RuntimeError, match="request failed"):
        preflight._request("GET", "/v1/functions")


def test_ensure_fixture_registers_local_execution_function(monkeypatch) -> None:
    preflight = SutPreflight(base_url="http://127.0.0.1:8080", fixture_name="tool-metrics-echo")
    calls: list[tuple[str, str, dict[str, object] | None]] = []
    demo_lookups = 0

    def _fake_request(method: str, path: str, payload=None):
        nonlocal demo_lookups
        calls.append((method, path, payload))
        if method == "GET" and path == "/v1/functions":
            return (200, "[]")
        if method == "GET" and path == "/v1/functions/tool-metrics-echo":
            return (404, "")
        if method == "GET" and path == "/v1/functions/demo-word-stats-deployment":
            demo_lookups += 1
            if demo_lookups == 1:
                return (404, "")
            return (
                200,
                '{"name":"demo-word-stats-deployment","executionMode":"DEPLOYMENT"}',
            )
        if method == "POST" and path == "/v1/functions":
            assert isinstance(payload, dict)
            if payload["name"] == "tool-metrics-echo":
                assert payload["executionMode"] == "LOCAL"
                return (201, json.dumps(payload))
            if payload["name"] == "demo-word-stats-deployment":
                assert payload["executionMode"] == "DEPLOYMENT"
                return (201, json.dumps(payload))
            raise AssertionError(f"unexpected function payload: {payload}")
        if method == "POST" and path == "/v1/functions/tool-metrics-echo:invoke":
            return (200, '{"status":"success","output":{"probe":"ok"}}')
        raise AssertionError(f"unexpected request: {method} {path}")

    monkeypatch.setattr(preflight, "_request", _fake_request)

    result = preflight.ensure_fixture()

    assert result.function_name == "tool-metrics-echo"
    assert result.registered is True
    assert result.warmup_status_code == 200
    assert calls[0] == ("GET", "/v1/functions", None)
    assert any(
        method == "POST"
        and path == "/v1/functions"
        and isinstance(payload, dict)
        and payload.get("name") == "demo-word-stats-deployment"
        for method, path, payload in calls
    )


def test_ensure_fixture_warmup_invocation_must_return_200(monkeypatch) -> None:
    preflight = SutPreflight(base_url="http://127.0.0.1:8080", fixture_name="tool-metrics-echo")

    def _fake_request(method: str, path: str, payload=None):
        if method == "GET" and path == "/v1/functions":
            return (200, "[]")
        if method == "GET" and path == "/v1/functions/tool-metrics-echo":
            return (200, '{"name":"tool-metrics-echo"}')
        if method == "POST" and path == "/v1/functions/tool-metrics-echo:invoke":
            return (500, '{"error":{"code":"LOCAL_ERROR"}}')
        raise AssertionError(f"unexpected request: {method} {path}")

    monkeypatch.setattr(preflight, "_request", _fake_request)

    with pytest.raises(RuntimeError, match="warm-up invocation failed"):
        preflight.ensure_fixture()


def test_ensure_fixture_verifies_demo_deployment_function(monkeypatch) -> None:
    preflight = SutPreflight(base_url="http://127.0.0.1:8080", fixture_name="tool-metrics-echo")

    def _fake_request(method: str, path: str, payload=None):
        if method == "GET" and path == "/v1/functions":
            return (200, "[]")
        if method == "GET" and path == "/v1/functions/tool-metrics-echo":
            return (200, '{"name":"tool-metrics-echo"}')
        if method == "POST" and path == "/v1/functions/tool-metrics-echo:invoke":
            return (200, '{"status":"success","output":{"probe":"ok"}}')
        if method == "GET" and path == "/v1/functions/demo-word-stats-deployment":
            return (200, '{"name":"demo-word-stats-deployment","executionMode":"POOL"}')
        raise AssertionError(f"unexpected request: {method} {path}")

    monkeypatch.setattr(preflight, "_request", _fake_request)

    with pytest.raises(RuntimeError, match="deployment demo verification failed"):
        preflight.ensure_fixture()


def test_ensure_fixture_accepts_effective_execution_mode_from_function_response(monkeypatch) -> None:
    preflight = SutPreflight(base_url="http://127.0.0.1:8080", fixture_name="tool-metrics-echo")

    def _fake_request(method: str, path: str, payload=None):
        if method == "GET" and path == "/v1/functions":
            return (200, "[]")
        if method == "GET" and path == "/v1/functions/tool-metrics-echo":
            return (200, '{"name":"tool-metrics-echo"}')
        if method == "POST" and path == "/v1/functions/tool-metrics-echo:invoke":
            return (200, '{"status":"success","output":{"probe":"ok"}}')
        if method == "GET" and path == "/v1/functions/demo-word-stats-deployment":
            return (
                200,
                '{"name":"demo-word-stats-deployment","requestedExecutionMode":"DEPLOYMENT","effectiveExecutionMode":"DEPLOYMENT"}',
            )
        raise AssertionError(f"unexpected request: {method} {path}")

    monkeypatch.setattr(preflight, "_request", _fake_request)

    result = preflight.ensure_fixture()

    assert result.function_name == "tool-metrics-echo"
    assert result.warmup_status_code == 200
