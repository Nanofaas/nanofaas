from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from controlplane_tool.loadtest.metrics import query_prometheus_range_series


@dataclass(frozen=True, slots=True)
class PrometheusQuerySpec:
    name: str
    query: str
    required: bool = False


PROMETHEUS_QUERIES: tuple[PrometheusQuerySpec, ...] = (
    PrometheusQuerySpec("function_dispatch_total", "function_dispatch_total", True),
    PrometheusQuerySpec("function_success_total", "function_success_total", True),
    PrometheusQuerySpec("function_error_total", "function_error_total", False),
    PrometheusQuerySpec("function_latency_ms", "function_latency_ms", False),
    PrometheusQuerySpec("function_e2e_latency_ms", "function_e2e_latency_ms", False),
    PrometheusQuerySpec("process_cpu_usage", "process_cpu_usage", False),
    PrometheusQuerySpec("jvm_memory_used_bytes", "jvm_memory_used_bytes", False),
    PrometheusQuerySpec("pod_count", "count(kube_pod_info)", False),
    PrometheusQuerySpec("pod_cpu_usage", "sum(rate(container_cpu_usage_seconds_total[1m]))", False),
    PrometheusQuerySpec("pod_memory_usage", "sum(container_memory_working_set_bytes)", False),
)


QueryRange = Callable[
    [str, str, datetime, datetime, int],
    list[dict[str, float | str]],
]


def capture_prometheus_snapshots(
    *,
    prometheus_url: str,
    output_dir: Path,
    start: datetime,
    end: datetime,
    specs: tuple[PrometheusQuerySpec, ...] = PROMETHEUS_QUERIES,
    query_range: QueryRange = query_prometheus_range_series,
    step_seconds: int = 5,
) -> Path:
    metrics_dir = output_dir / "metrics"
    metrics_dir.mkdir(parents=True, exist_ok=True)
    queries: dict[str, dict[str, object]] = {}

    for spec in specs:
        entry: dict[str, object] = {
            "query": spec.query,
            "required": spec.required,
            "points": [],
        }
        try:
            points = query_range(prometheus_url, spec.query, start, end, step_seconds)
        except RuntimeError as exc:
            if spec.required:
                raise RuntimeError(f"required prometheus query '{spec.name}' failed: {exc}") from exc
            entry["error"] = str(exc)
            queries[spec.name] = entry
            continue

        if spec.required and not points:
            raise RuntimeError(f"required prometheus query '{spec.name}' returned no data")
        entry["points"] = points
        queries[spec.name] = entry

    snapshot = {
        "prometheus_url": prometheus_url,
        "start": start.isoformat(),
        "end": end.isoformat(),
        "queries": queries,
    }
    destination = metrics_dir / "prometheus-snapshots.json"
    destination.write_text(json.dumps(snapshot, indent=2), encoding="utf-8")
    return destination
