from __future__ import annotations

from datetime import datetime, timezone
import json
from pathlib import Path
import re
from typing import Any
from urllib.error import URLError
from urllib.parse import urlencode
from urllib.request import urlopen

_METRIC_NAME = re.compile(r"^[a-zA-Z_:][a-zA-Z0-9_:]*$")
_JAVA_METRIC_LITERAL = re.compile(r'"(function_[a-z0-9_]+(?:_ms|_total)?)"')


def parse_prometheus_metric_names(payload: str) -> set[str]:
    names: set[str] = set()
    for line in payload.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        metric_name = stripped.split("{", 1)[0].split(" ", 1)[0]
        if _METRIC_NAME.match(metric_name):
            names.add(metric_name)
    return names


def missing_required_metrics(required: list[str], observed_names: set[str]) -> list[str]:
    return [name for name in required if name not in observed_names]


def parse_prometheus_sample_values(payload: str) -> dict[str, float]:
    values: dict[str, float] = {}
    for line in payload.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        tokens = stripped.split()
        if len(tokens) < 2:
            continue
        metric_name = tokens[0].split("{", 1)[0]
        if not _METRIC_NAME.match(metric_name):
            continue
        try:
            sample_value = float(tokens[1])
        except ValueError:
            continue
        values[metric_name] = values.get(metric_name, 0.0) + sample_value
    return values


def build_required_metric_series(
    snapshots: list[tuple[str, str]],
    required: list[str],
) -> dict[str, list[dict[str, float | str]]]:
    series: dict[str, list[dict[str, float | str]]] = {name: [] for name in required}
    for timestamp, payload in snapshots:
        sample_values = parse_prometheus_sample_values(payload)
        for metric_name in required:
            series[metric_name].append(
                {
                    "timestamp": timestamp,
                    "value": float(sample_values.get(metric_name, 0.0)),
                }
            )
    return series


def discover_control_plane_metric_names(repo_root: Path) -> set[str]:
    metrics_java = (
        repo_root
        / "control-plane"
        / "src"
        / "main"
        / "java"
        / "it"
        / "unimib"
        / "datai"
        / "nanofaas"
        / "controlplane"
        / "service"
        / "Metrics.java"
    )
    if not metrics_java.exists():
        return set()
    text = metrics_java.read_text(encoding="utf-8")
    return set(_JAVA_METRIC_LITERAL.findall(text))


def _prometheus_api_get(
    base_url: str, path: str, params: dict[str, str], timeout_seconds: float = 4.0
) -> Any:
    query = urlencode(params)
    normalized = base_url.rstrip("/")
    url = f"{normalized}{path}?{query}" if query else f"{normalized}{path}"
    try:
        with urlopen(url, timeout=timeout_seconds) as response:
            payload = response.read().decode("utf-8")
        data = json.loads(payload)
    except (OSError, URLError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"prometheus api request failed for {path}: {exc}") from exc
    if data.get("status") != "success":
        raise RuntimeError(f"prometheus api failed for {path}: {data}")
    return data.get("data")


def query_prometheus_metric_names(base_url: str) -> set[str]:
    data = _prometheus_api_get(base_url, "/api/v1/label/__name__/values", {})
    if not isinstance(data, list):
        raise RuntimeError("invalid prometheus metric names payload")
    names: set[str] = set()
    for value in data:
        if isinstance(value, str) and _METRIC_NAME.match(value):
            names.add(value)
    return names


def query_prometheus_range_series(
    base_url: str,
    metric_name: str,
    start: datetime,
    end: datetime,
    step_seconds: int = 2,
) -> list[dict[str, float | str]]:
    data = _prometheus_api_get(
        base_url,
        "/api/v1/query_range",
        {
            "query": metric_name,
            "start": str(start.timestamp()),
            "end": str(end.timestamp()),
            "step": f"{step_seconds}s",
        },
    )
    if not isinstance(data, dict):
        raise RuntimeError("invalid prometheus query_range payload")
    result = data.get("result", [])
    if not isinstance(result, list):
        raise RuntimeError("invalid prometheus query_range payload")

    # Merge samples across label dimensions by timestamp.
    merged: dict[float, float] = {}
    for series in result:
        if not isinstance(series, dict):
            continue
        values = series.get("values", [])
        if not isinstance(values, list):
            continue
        for sample in values:
            if not isinstance(sample, list) or len(sample) != 2:
                continue
            raw_ts, raw_value = sample
            try:
                ts = float(raw_ts)
                value = float(raw_value)
            except (TypeError, ValueError):
                continue
            merged[ts] = merged.get(ts, 0.0) + value

    points: list[dict[str, float | str]] = []
    for timestamp in sorted(merged):
        iso = datetime.fromtimestamp(timestamp, timezone.utc).isoformat()
        points.append({"timestamp": iso, "value": float(merged[timestamp])})
    return points
