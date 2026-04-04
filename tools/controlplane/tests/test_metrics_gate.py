"""
Tests for metrics_gate — evaluate_metrics_gate and query helpers.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import pytest

from controlplane_tool.metrics_gate import (
    _query_candidates_for_metric,
    evaluate_metrics_gate,
)
from controlplane_tool.loadtest_catalog import resolve_load_profile
from controlplane_tool.loadtest_models import LoadtestRequest, MetricsGate
from controlplane_tool.models import ControlPlaneConfig, MetricsConfig, Profile, TestsConfig
from controlplane_tool.scenario_loader import load_scenario_file


# ---------------------------------------------------------------------------
# _query_candidates_for_metric
# ---------------------------------------------------------------------------

def test_query_candidates_includes_metric_name_itself() -> None:
    candidates = _query_candidates_for_metric("function_dispatch_total")
    assert "function_dispatch_total" in candidates


def test_query_candidates_adds_aliases_for_ms_metrics() -> None:
    candidates = _query_candidates_for_metric("function_latency_ms")
    assert "function_latency_ms_seconds_count" in candidates
    assert "function_latency_ms_count" in candidates


def test_query_candidates_no_aliases_for_non_ms_metrics() -> None:
    candidates = _query_candidates_for_metric("function_enqueue_total")
    assert len(candidates) == 1


# ---------------------------------------------------------------------------
# evaluate_metrics_gate
# ---------------------------------------------------------------------------

def _profile(required: list[str] | None = None) -> Profile:
    return Profile(
        name="qa",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="jvm"),
        modules=[],
        tests=TestsConfig(enabled=True, metrics=True),
        metrics=MetricsConfig(required=required or ["function_dispatch_total"]),
    )


@dataclass
class FakeContext:
    base_url: str = "http://127.0.0.1:8080"
    prometheus_url: str = "http://127.0.0.1:9090"
    scenario_manifest_path: Path = Path("/tmp/manifest.json")
    target_functions: list = None  # type: ignore[assignment]
    target_results: list = None  # type: ignore[assignment]
    started_at: datetime = None  # type: ignore[assignment]
    prometheus_session: object = None

    def __post_init__(self) -> None:
        if self.target_functions is None:
            self.target_functions = ["echo-test"]
        if self.target_results is None:
            self.target_results = []
        if self.started_at is None:
            self.started_at = datetime.now(timezone.utc)


def _make_request(required: list[str] | None = None) -> LoadtestRequest:
    return LoadtestRequest(
        name="test",
        profile=_profile(required),
        scenario=load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml")),
        load_profile=resolve_load_profile("quick"),
        metrics_gate=MetricsGate(required_metrics=required or ["function_dispatch_total"]),
    )


def test_evaluate_metrics_gate_passes_when_all_metrics_present(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(
        "controlplane_tool.metrics_gate.query_prometheus_metric_names",
        lambda url: {"function_dispatch_total"},
    )
    monkeypatch.setattr(
        "controlplane_tool.metrics_gate.query_prometheus_range_series",
        lambda base_url, metric_name, start, end, step_seconds=2: [
            {"timestamp": start.isoformat(), "value": 1.0},
        ],
    )
    ok, detail = evaluate_metrics_gate(_profile(), _make_request(), FakeContext(), tmp_path)
    assert ok is True
    assert "prometheus checks passed" in detail


def test_evaluate_metrics_gate_fails_when_metric_missing(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(
        "controlplane_tool.metrics_gate.query_prometheus_metric_names",
        lambda url: {"something_else"},
    )
    monkeypatch.setattr(
        "controlplane_tool.metrics_gate.query_prometheus_range_series",
        lambda base_url, metric_name, start, end, step_seconds=2: [],
    )
    ok, detail = evaluate_metrics_gate(_profile(), _make_request(), FakeContext(), tmp_path)
    assert ok is False
    assert "missing required metrics" in detail


def test_evaluate_metrics_gate_warns_when_mode_warn(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(
        "controlplane_tool.metrics_gate.query_prometheus_metric_names",
        lambda url: set(),
    )
    monkeypatch.setattr(
        "controlplane_tool.metrics_gate.query_prometheus_range_series",
        lambda base_url, metric_name, start, end, step_seconds=2: [],
    )
    request = LoadtestRequest(
        name="test",
        profile=_profile(),
        scenario=load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml")),
        load_profile=resolve_load_profile("quick"),
        metrics_gate=MetricsGate(required_metrics=["function_dispatch_total"], mode="warn"),
    )
    ok, detail = evaluate_metrics_gate(_profile(), request, FakeContext(), tmp_path)
    assert ok is True
    assert "warning" in detail


def test_evaluate_metrics_gate_passes_when_mode_off(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(
        "controlplane_tool.metrics_gate.query_prometheus_metric_names",
        lambda url: set(),
    )
    monkeypatch.setattr(
        "controlplane_tool.metrics_gate.query_prometheus_range_series",
        lambda base_url, metric_name, start, end, step_seconds=2: [],
    )
    request = LoadtestRequest(
        name="test",
        profile=_profile(),
        scenario=load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml")),
        load_profile=resolve_load_profile("quick"),
        metrics_gate=MetricsGate(required_metrics=["function_dispatch_total"], mode="off"),
    )
    ok, detail = evaluate_metrics_gate(_profile(), request, FakeContext(), tmp_path)
    assert ok is True
    assert "disabled" in detail


def test_evaluate_metrics_gate_writes_series_json(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(
        "controlplane_tool.metrics_gate.query_prometheus_metric_names",
        lambda url: {"function_dispatch_total"},
    )
    monkeypatch.setattr(
        "controlplane_tool.metrics_gate.query_prometheus_range_series",
        lambda base_url, metric_name, start, end, step_seconds=2: [
            {"timestamp": start.isoformat(), "value": 1.0},
        ],
    )
    evaluate_metrics_gate(_profile(), _make_request(), FakeContext(), tmp_path)
    series_path = tmp_path / "metrics" / "series.json"
    assert series_path.exists()
    data = json.loads(series_path.read_text())
    assert "function_dispatch_total" in data


def test_evaluate_metrics_gate_returns_error_on_prometheus_failure(tmp_path: Path, monkeypatch) -> None:
    def _raise(url: str) -> set[str]:
        raise RuntimeError("connection refused")
    monkeypatch.setattr("controlplane_tool.metrics_gate.query_prometheus_metric_names", _raise)
    monkeypatch.setattr(
        "controlplane_tool.metrics_gate.query_prometheus_range_series",
        lambda *a, **kw: [],
    )
    ok, detail = evaluate_metrics_gate(_profile(), _make_request(), FakeContext(), tmp_path)
    assert ok is False
    assert "prometheus metrics query failed" in detail
