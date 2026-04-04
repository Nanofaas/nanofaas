"""
Tests for metrics.py — parsing and query helpers.
"""
from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from controlplane_tool.metrics import (
    build_required_metric_series,
    discover_control_plane_metric_names,
    missing_required_metrics,
    parse_prometheus_metric_names,
    parse_prometheus_sample_values,
    query_prometheus_metric_names,
    query_prometheus_range_series,
)


# ---------------------------------------------------------------------------
# parse_prometheus_metric_names
# ---------------------------------------------------------------------------

def test_parse_metric_names_ignores_comment_lines() -> None:
    payload = "# HELP foo a metric\n# TYPE foo counter\nfoo 1\n"
    names = parse_prometheus_metric_names(payload)
    assert names == {"foo"}


def test_parse_metric_names_strips_labels() -> None:
    payload = 'http_requests_total{method="GET"} 42\n'
    names = parse_prometheus_metric_names(payload)
    assert "http_requests_total" in names


def test_parse_metric_names_ignores_empty_lines() -> None:
    payload = "\n\nfoo 1\n\nbar 2\n"
    names = parse_prometheus_metric_names(payload)
    assert names == {"foo", "bar"}


def test_parse_metric_names_empty_payload() -> None:
    assert parse_prometheus_metric_names("") == set()


def test_parse_metric_names_returns_all_unique_names() -> None:
    payload = "foo 1\nfoo 2\nbar 3\n"
    names = parse_prometheus_metric_names(payload)
    assert names == {"foo", "bar"}


# ---------------------------------------------------------------------------
# missing_required_metrics
# ---------------------------------------------------------------------------

def test_missing_required_metrics_returns_empty_when_all_present() -> None:
    assert missing_required_metrics(["foo", "bar"], {"foo", "bar", "baz"}) == []


def test_missing_required_metrics_returns_missing_names() -> None:
    missing = missing_required_metrics(["foo", "bar"], {"foo"})
    assert missing == ["bar"]


def test_missing_required_metrics_returns_all_when_none_present() -> None:
    missing = missing_required_metrics(["foo", "bar"], set())
    assert set(missing) == {"foo", "bar"}


# ---------------------------------------------------------------------------
# parse_prometheus_sample_values
# ---------------------------------------------------------------------------

def test_parse_sample_values_returns_float_values() -> None:
    payload = "foo 1.5\nbar 2.0\n"
    values = parse_prometheus_sample_values(payload)
    assert values["foo"] == pytest.approx(1.5)
    assert values["bar"] == pytest.approx(2.0)


def test_parse_sample_values_sums_across_labels() -> None:
    payload = 'foo{a="1"} 3\nfoo{a="2"} 4\n'
    values = parse_prometheus_sample_values(payload)
    assert values["foo"] == pytest.approx(7.0)


def test_parse_sample_values_ignores_comments() -> None:
    payload = "# HELP foo desc\nfoo 1\n"
    values = parse_prometheus_sample_values(payload)
    assert values == {"foo": pytest.approx(1.0)}


def test_parse_sample_values_ignores_invalid_tokens() -> None:
    payload = "foo abc\nbar 2\n"
    values = parse_prometheus_sample_values(payload)
    assert "foo" not in values
    assert values.get("bar") == pytest.approx(2.0)


# ---------------------------------------------------------------------------
# build_required_metric_series
# ---------------------------------------------------------------------------

def test_build_series_includes_all_required_keys() -> None:
    series = build_required_metric_series(
        snapshots=[("2024-01-01T00:00:00", "foo 1\n")],
        required=["foo", "bar"],
    )
    assert "foo" in series
    assert "bar" in series


def test_build_series_records_timestamp_and_value() -> None:
    series = build_required_metric_series(
        snapshots=[("2024-01-01T00:00:00", "foo 5\n")],
        required=["foo"],
    )
    assert series["foo"][0]["value"] == pytest.approx(5.0)
    assert series["foo"][0]["timestamp"] == "2024-01-01T00:00:00"


def test_build_series_defaults_missing_metric_to_zero() -> None:
    series = build_required_metric_series(
        snapshots=[("ts1", "other_metric 1\n")],
        required=["foo"],
    )
    assert series["foo"][0]["value"] == pytest.approx(0.0)


def test_build_series_empty_snapshots() -> None:
    series = build_required_metric_series(snapshots=[], required=["foo"])
    assert series == {"foo": []}


# ---------------------------------------------------------------------------
# discover_control_plane_metric_names
# ---------------------------------------------------------------------------

def test_discover_metric_names_returns_empty_when_file_missing(tmp_path: Path) -> None:
    names = discover_control_plane_metric_names(tmp_path)
    assert names == set()


def test_discover_metric_names_extracts_quoted_identifiers(tmp_path: Path) -> None:
    metrics_dir = (
        tmp_path
        / "control-plane" / "src" / "main" / "java"
        / "it" / "unimib" / "datai" / "nanofaas" / "controlplane" / "service"
    )
    metrics_dir.mkdir(parents=True)
    (metrics_dir / "Metrics.java").write_text(
        'Counter.builder("function_dispatch_total").register();\n'
        'Timer.builder("function_latency_ms").register();\n',
        encoding="utf-8",
    )
    names = discover_control_plane_metric_names(tmp_path)
    assert "function_dispatch_total" in names
    assert "function_latency_ms" in names


# ---------------------------------------------------------------------------
# query_prometheus_metric_names
# ---------------------------------------------------------------------------

def _mock_urlopen(payload: dict):
    mock_response = MagicMock()
    mock_response.__enter__ = lambda s: s
    mock_response.__exit__ = MagicMock(return_value=False)
    mock_response.read.return_value = json.dumps(payload).encode()
    return mock_response


def test_query_metric_names_returns_set(monkeypatch) -> None:
    payload = {"status": "success", "data": ["foo", "bar", "baz"]}
    with patch("controlplane_tool.metrics.urlopen", return_value=_mock_urlopen(payload)):
        names = query_prometheus_metric_names("http://localhost:9090")
    assert names == {"foo", "bar", "baz"}


def test_query_metric_names_raises_on_non_success(monkeypatch) -> None:
    payload = {"status": "error", "error": "bad request"}
    with patch("controlplane_tool.metrics.urlopen", return_value=_mock_urlopen(payload)):
        with pytest.raises(RuntimeError, match="prometheus api failed"):
            query_prometheus_metric_names("http://localhost:9090")


def test_query_metric_names_raises_on_connection_error(monkeypatch) -> None:
    with patch("controlplane_tool.metrics.urlopen", side_effect=OSError("refused")):
        with pytest.raises(RuntimeError, match="prometheus api request failed"):
            query_prometheus_metric_names("http://localhost:9090")


# ---------------------------------------------------------------------------
# query_prometheus_range_series
# ---------------------------------------------------------------------------

def test_query_range_series_merges_label_dimensions(monkeypatch) -> None:
    ts = 1700000000.0
    payload = {
        "status": "success",
        "data": {
            "result": [
                {"metric": {"label": "a"}, "values": [[ts, "3"]]},
                {"metric": {"label": "b"}, "values": [[ts, "4"]]},
            ]
        },
    }
    start = datetime.fromtimestamp(ts - 60, timezone.utc)
    end = datetime.fromtimestamp(ts + 60, timezone.utc)
    with patch("controlplane_tool.metrics.urlopen", return_value=_mock_urlopen(payload)):
        points = query_prometheus_range_series("http://localhost:9090", "foo", start, end)
    assert len(points) == 1
    assert points[0]["value"] == pytest.approx(7.0)


def test_query_range_series_returns_sorted_timestamps(monkeypatch) -> None:
    ts1, ts2 = 1700000000.0, 1700000010.0
    payload = {
        "status": "success",
        "data": {
            "result": [{"metric": {}, "values": [[ts2, "2"], [ts1, "1"]]}],
        },
    }
    start = datetime.fromtimestamp(ts1 - 60, timezone.utc)
    end = datetime.fromtimestamp(ts2 + 60, timezone.utc)
    with patch("controlplane_tool.metrics.urlopen", return_value=_mock_urlopen(payload)):
        points = query_prometheus_range_series("http://localhost:9090", "foo", start, end)
    assert points[0]["value"] == pytest.approx(1.0)
    assert points[1]["value"] == pytest.approx(2.0)


def test_query_range_series_returns_empty_on_no_results(monkeypatch) -> None:
    payload = {"status": "success", "data": {"result": []}}
    start = datetime(2024, 1, 1, tzinfo=timezone.utc)
    end = datetime(2024, 1, 2, tzinfo=timezone.utc)
    with patch("controlplane_tool.metrics.urlopen", return_value=_mock_urlopen(payload)):
        points = query_prometheus_range_series("http://localhost:9090", "foo", start, end)
    assert points == []
