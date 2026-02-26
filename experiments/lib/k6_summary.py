from __future__ import annotations

from typing import Any, Mapping


def _to_non_negative_int(value: Any) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return 0
    return max(0, parsed)


def _to_ratio_01(value: Any) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return 0.0
    if parsed < 0.0:
        return 0.0
    if parsed > 1.0:
        return 1.0
    return parsed


def resolve_http_req_failed_count(http_req_failed_metric: Mapping[str, Any] | None, reqs: int) -> int:
    """
    Resolve failed request count from k6 `http_req_failed` summary metric.

    k6 exports the metric as a Rate:
    - `value` is the failed ratio
    - `passes` is the number of samples where the metric is true (failed requests)
    - `fails` is the number of samples where the metric is false (successful requests)
    """
    total_reqs = max(0, int(reqs))
    metric = dict(http_req_failed_metric or {})

    if "value" in metric:
        return int(round(_to_ratio_01(metric.get("value")) * total_reqs))
    if "passes" in metric:
        return min(total_reqs, _to_non_negative_int(metric.get("passes")))
    if "fails" in metric:
        successful = min(total_reqs, _to_non_negative_int(metric.get("fails")))
        return max(0, total_reqs - successful)
    return 0


def resolve_http_req_failed_ratio(http_req_failed_metric: Mapping[str, Any] | None, reqs: int) -> float:
    total_reqs = max(0, int(reqs))
    if total_reqs == 0:
        return 0.0
    fails = resolve_http_req_failed_count(http_req_failed_metric, total_reqs)
    return fails / float(total_reqs)
