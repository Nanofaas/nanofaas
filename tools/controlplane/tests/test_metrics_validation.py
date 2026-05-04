import httpx
import pytest
from unittest.mock import MagicMock

from controlplane_tool.loadtest.metrics import (
    build_required_metric_series,
    missing_required_metrics,
    parse_prometheus_metric_names,
    query_prometheus_metric_names,
)


def test_parse_prometheus_metric_names_and_detect_missing() -> None:
    payload = """
# HELP function_dispatch_total Dispatch attempts.
# TYPE function_dispatch_total counter
function_dispatch_total{function=\"echo\"} 3.0
# HELP process_cpu_usage CPU usage
# TYPE process_cpu_usage gauge
process_cpu_usage 0.52
"""
    names = parse_prometheus_metric_names(payload)
    missing = missing_required_metrics(
        required=["function_dispatch_total", "process_cpu_usage", "function_latency_ms"],
        observed_names=names,
    )

    assert "function_dispatch_total" in names
    assert "process_cpu_usage" in names
    assert missing == ["function_latency_ms"]


def test_build_required_metric_series_from_prometheus_payloads() -> None:
    snapshots = [
        (
            "2026-02-26T13:00:00Z",
            "function_dispatch_total{function=\"echo\"} 1\nfunction_error_total{function=\"echo\"} 0\n",
        ),
        (
            "2026-02-26T13:00:10Z",
            "function_dispatch_total{function=\"echo\"} 3\nfunction_error_total{function=\"echo\"} 1\n",
        ),
    ]
    series = build_required_metric_series(
        snapshots=snapshots,
        required=["function_dispatch_total", "function_error_total", "function_latency_ms"],
    )

    assert series["function_dispatch_total"][0]["value"] == 1.0
    assert series["function_dispatch_total"][1]["value"] == 3.0
    assert series["function_error_total"][1]["value"] == 1.0
    assert series["function_latency_ms"][0]["value"] == 0.0


def test_query_prometheus_metric_names_wraps_transport_errors(monkeypatch) -> None:
    monkeypatch.setattr(
        "controlplane_tool.loadtest.metrics.httpx.get",
        lambda *args, **kwargs: (_ for _ in ()).throw(httpx.RequestError("connection refused")),
    )

    with pytest.raises(RuntimeError, match="prometheus api request failed"):
        query_prometheus_metric_names("http://127.0.0.1:9090")


def test_query_prometheus_metric_names_accepts_list_data_payload(monkeypatch) -> None:
    payload = {"status": "success", "data": ["function_dispatch_total", "function_latency_ms", "up"]}
    mock_response = MagicMock(spec=httpx.Response)
    mock_response.json.return_value = payload
    monkeypatch.setattr("controlplane_tool.loadtest.metrics.httpx.get", lambda *a, **kw: mock_response)

    names = query_prometheus_metric_names("http://127.0.0.1:9090")

    assert "function_dispatch_total" in names
    assert "function_latency_ms" in names
