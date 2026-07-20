"""Pure reconciliation of k6 offload traffic against edge/cloud Prometheus counters.

No I/O: callers fetch the k6 summary JSON and the two raw Prometheus exposition
texts (e.g. via ``CapturePrometheusSnapshot``) and pass them in.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True, slots=True)
class ConservationReport:
    passed: bool
    failures: tuple[str, ...]
    numbers: dict[str, float]


def _k6_counter_value(
    k6_summary: Mapping[str, Any], name: str, tags: Mapping[str, str] | None = None
) -> float:
    """Read a k6 --summary-export counter's count.

    k6's JSON summary stores counter fields flat on the metric object (e.g.
    ``{"count": 4, "rate": 0.06}``), not nested under a "values" key. It also
    only emits a per-tag submetric (key ``"name{tag:value}"``) for tag
    combinations referenced by a threshold; untagged custom counters are the
    reliable source for anything else.
    """
    metrics = k6_summary.get("metrics")
    if not isinstance(metrics, Mapping):
        return 0.0
    if tags:
        tag_str = ",".join(f"{key}:{value}" for key, value in tags.items())
        submetric = metrics.get(f"{name}{{{tag_str}}}")
        if isinstance(submetric, Mapping) and "count" in submetric:
            try:
                return float(submetric["count"])
            except (TypeError, ValueError):
                pass
    aggregate = metrics.get(name)
    if isinstance(aggregate, Mapping) and "count" in aggregate:
        try:
            return float(aggregate["count"])
        except (TypeError, ValueError):
            pass
    return 0.0


def _sum_metric(text: str, prefix: str) -> float:
    total = 0.0
    for line in text.splitlines():
        if line.startswith(prefix):
            try:
                total += float(line.rsplit(" ", 1)[-1])
            except ValueError:
                continue
    return total


def evaluate_conservation(
    *,
    k6_summary: Mapping[str, Any],
    edge_metrics: str,
    cloud_metrics: str,
    offloadable: str,
    control: str,
    tolerance: int = 5,
) -> ConservationReport:
    failures: list[str] = []
    numbers: dict[str, float] = {}

    def record(label: str, value: float) -> float:
        numbers[label] = value
        return value

    def check_close(a_label: str, a: float, b_label: str, b: float) -> None:
        if abs(a - b) > tolerance:
            failures.append(
                f"{a_label} ({a}) diverges from {b_label} ({b}) beyond tolerance {tolerance}"
            )

    # 1. k6 requests for the offloadable function vs edge function_success_total.
    k6_offloadable_reqs = record(
        "k6_offloadable_requests",
        _k6_counter_value(k6_summary, "offloadable_requests"),
    )
    edge_success_offloadable = record(
        "edge_function_success_offloadable",
        _sum_metric(edge_metrics, f'function_success_total{{function="{offloadable}"}}'),
    )
    check_close(
        "k6 requests for offloadable",
        k6_offloadable_reqs,
        "edge function_success_total for offloadable",
        edge_success_offloadable,
    )

    # 2. k6 offloaded_requests vs edge nanofaas_offload_total (depth+est_wait) vs cloud success.
    k6_offloaded = record(
        "k6_offloaded_requests",
        _k6_counter_value(k6_summary, "offloaded_requests", {"function": offloadable}),
    )
    edge_offload_total = record(
        "edge_offload_total",
        _sum_metric(edge_metrics, f'nanofaas_offload_total{{function="{offloadable}",'),
    )
    cloud_success_offloadable = record(
        "cloud_function_success_offloadable",
        _sum_metric(cloud_metrics, f'function_success_total{{function="{offloadable}"}}'),
    )
    check_close(
        "k6 offloaded_requests",
        k6_offloaded,
        "edge nanofaas_offload_total (depth+est_wait)",
        edge_offload_total,
    )
    check_close(
        "edge nanofaas_offload_total (depth+est_wait)",
        edge_offload_total,
        "cloud function_success_total for offloadable",
        cloud_success_offloadable,
    )
    check_close(
        "k6 offloaded_requests",
        k6_offloaded,
        "cloud function_success_total for offloadable",
        cloud_success_offloadable,
    )
    if k6_offloaded <= 0:
        failures.append(
            "no requests were offloaded (k6 offloaded_requests == 0); "
            "the experiment must actually offload"
        )

    # 3. the control function must never be offloaded, on either control plane.
    edge_offload_control = record(
        "edge_offload_control",
        _sum_metric(edge_metrics, f'nanofaas_offload_total{{function="{control}",'),
    )
    if edge_offload_control > tolerance:
        failures.append(
            f"edge nanofaas_offload_total for control function {control} "
            f"is {edge_offload_control}, expected 0"
        )
    if control in cloud_metrics:
        failures.append(f"cloud metrics mention the control function {control}; it must never run there")

    # 4. no offload failures, no retries, on the edge.
    if "nanofaas_offload_failure_total" in edge_metrics:
        failures.append("edge exposes nanofaas_offload_failure_total; offload calls must never fail")
    for function in (offloadable, control):
        retries = _sum_metric(edge_metrics, f'function_retry_total{{function="{function}"}}')
        if retries > tolerance:
            failures.append(f"edge function_retry_total for {function} is {retries}, expected 0")

    return ConservationReport(passed=not failures, failures=tuple(failures), numbers=numbers)
