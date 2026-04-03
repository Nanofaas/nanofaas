import pytest

from controlplane_tool.metrics import (
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
    def _raise(*args, **kwargs):  # noqa: ARG001
        raise OSError("connection refused")

    monkeypatch.setattr("controlplane_tool.metrics.urlopen", _raise)

    with pytest.raises(RuntimeError, match="prometheus api request failed"):
        query_prometheus_metric_names("http://127.0.0.1:9090")


def test_query_prometheus_metric_names_accepts_list_data_payload(monkeypatch) -> None:
    class _Response:
        status = 200

        def __init__(self, payload: str) -> None:
            self._payload = payload

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):  # noqa: ANN001
            return None

        def read(self) -> bytes:
            return self._payload.encode("utf-8")

    payload = (
        '{"status":"success","data":["function_dispatch_total","function_latency_ms","up"]}'
    )
    monkeypatch.setattr(
        "controlplane_tool.metrics.urlopen",
        lambda url, timeout=4.0: _Response(payload),  # noqa: ARG005
    )

    names = query_prometheus_metric_names("http://127.0.0.1:9090")

    assert "function_dispatch_total" in names
    assert "function_latency_ms" in names
