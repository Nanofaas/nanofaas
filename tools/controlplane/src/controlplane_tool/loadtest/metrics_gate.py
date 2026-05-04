"""
metrics_gate.py

Prometheus metrics gate evaluation.

Extracted from adapters.py (ShellCommandAdapter) to satisfy single-responsibility.
"""
from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

from controlplane_tool.loadtest.loadtest_models import LoadtestRequest
from controlplane_tool.loadtest.metrics import (
    missing_required_metrics,
    query_prometheus_metric_names,
    query_prometheus_range_series,
)
from controlplane_tool.core.models import Profile


def _query_candidates_for_metric(metric_name: str) -> list[str]:
    candidates = [metric_name]
    if metric_name.endswith("_ms"):
        candidates.append(f"{metric_name}_seconds_count")
        candidates.append(f"{metric_name}_count")
    return candidates


def _query_series_with_aliases(
    base_url: str,
    metric_name: str,
    start: datetime,
    end: datetime,
) -> list[dict[str, float | str]]:
    for candidate in _query_candidates_for_metric(metric_name):
        points = query_prometheus_range_series(
            base_url=base_url,
            metric_name=candidate,
            start=start,
            end=end,
            step_seconds=2,
        )
        if points:
            return points
    return []


def _write_metrics_artifacts(
    *,
    metrics_dir: Path,
    series: dict[str, list[dict[str, float | str]]],
    context: object,
    request: LoadtestRequest,
    configured_required_metrics: list[str],
    gate_required_metrics: list[str],
    observed_run_metrics: set[str],
    available_metrics: set[str],
    missing: list[str],
) -> None:
    (metrics_dir / "series.json").write_text(json.dumps(series, indent=2), encoding="utf-8")
    (metrics_dir / "observed-metrics.json").write_text(
        json.dumps(
            {
                "source": "prometheus-api",
                "endpoint": context.prometheus_url,  # type: ignore[union-attr]
                "owned_container": bool(
                    getattr(context.prometheus_session, "owned_container_name", None)  # type: ignore[union-attr]
                ),
                "scenario_manifest": str(context.scenario_manifest_path),  # type: ignore[union-attr]
                "load_profile": request.load_profile.name,
                "observed_run_window": sorted(observed_run_metrics),
                "available_in_prometheus": sorted(available_metrics),
                "required_gate": gate_required_metrics,
                "required_configured": configured_required_metrics,
                "missing": missing,
            },
            indent=2,
        ),
        encoding="utf-8",
    )


def evaluate_metrics_gate(
    profile: Profile,
    request: LoadtestRequest,
    context: object,
    run_dir: Path,
) -> tuple[bool, str]:
    """Query Prometheus and check required metrics were observed during the loadtest window."""
    metrics_dir = run_dir / "metrics"
    metrics_dir.mkdir(parents=True, exist_ok=True)

    started_at = context.started_at  # type: ignore[union-attr]
    ended_at = datetime.now(timezone.utc)
    if ended_at <= started_at:
        ended_at = started_at + timedelta(
            seconds=max(1, request.load_profile.summary_window_seconds)
        )

    from controlplane_tool.loadtest.loadtest_models import effective_required_metrics

    configured_required_metrics = list(profile.metrics.required)
    gate_required_metrics = (
        list(request.metrics_gate.required_metrics)
        if request.metrics_gate.required_metrics
        else effective_required_metrics(profile)
    )
    query_metrics = sorted(set(configured_required_metrics) | set(gate_required_metrics))

    observed_run_metrics: set[str] = set()
    series: dict[str, list[dict[str, float | str]]] = {}
    try:
        for metric in query_metrics:
            points = _query_series_with_aliases(
                base_url=context.prometheus_url,  # type: ignore[union-attr]
                metric_name=metric,
                start=started_at,
                end=ended_at,
            )
            if not points:
                points = [
                    {"timestamp": started_at.isoformat(), "value": 0.0},
                    {"timestamp": ended_at.isoformat(), "value": 0.0},
                ]
            else:
                observed_run_metrics.add(metric)
            series[metric] = points

        missing = missing_required_metrics(gate_required_metrics, observed_run_metrics)
        available_metrics = query_prometheus_metric_names(context.prometheus_url)  # type: ignore[union-attr]
    except RuntimeError as exc:
        return (False, f"prometheus metrics query failed: {exc}")

    _write_metrics_artifacts(
        metrics_dir=metrics_dir,
        series=series,
        context=context,
        request=request,
        configured_required_metrics=configured_required_metrics,
        gate_required_metrics=gate_required_metrics,
        observed_run_metrics=observed_run_metrics,
        available_metrics=available_metrics,
        missing=missing,
    )

    if missing and request.metrics_gate.mode == "off":
        return (True, "metrics gate disabled")
    if missing and request.metrics_gate.mode == "warn":
        return (True, "metrics gate warning: missing required metrics: " + ", ".join(missing))
    if missing:
        return (
            False,
            "missing required metrics: "
            + ", ".join(missing)
            + " (see metrics/observed-metrics.json)",
        )
    return (True, "prometheus checks passed")
