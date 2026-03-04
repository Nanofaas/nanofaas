from __future__ import annotations

import json

import pytest

from controlplane_tool.sut_preflight import SutPreflight


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
