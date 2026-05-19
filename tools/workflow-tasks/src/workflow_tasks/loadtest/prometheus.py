from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

import httpx


def _prometheus_api_get(
    base_url: str, path: str, params: dict[str, str], timeout_seconds: float = 4.0
) -> Any:
    url = f"{base_url.rstrip('/')}{path}"
    try:
        response = httpx.get(url, params=params, timeout=timeout_seconds)
        data = response.json()
    except Exception as exc:
        raise RuntimeError(f"prometheus api request failed for {path}: {exc}") from exc
    if data.get("status") != "success":
        raise RuntimeError(f"prometheus api failed for {path}: {data}")
    return data.get("data")


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
