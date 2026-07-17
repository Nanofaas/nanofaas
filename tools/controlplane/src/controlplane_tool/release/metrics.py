"""Pure aggregation and rendering for release performance evidence."""

from __future__ import annotations

import json
import math
import statistics
from dataclasses import asdict, dataclass, fields
from typing import Any, Mapping, Sequence


@dataclass(frozen=True, slots=True)
class PerformanceProfile:
    name: str
    provider: str
    stack_vm: str
    loadgen_vm: str
    architecture: str
    flavor: str
    scenario: str


@dataclass(frozen=True, slots=True)
class RegressionPolicy:
    throughput_max_loss_percent: float
    p95_max_increase_percent: float
    error_rate_max: float


@dataclass(frozen=True, slots=True)
class PerformanceAggregate:
    profile: PerformanceProfile
    run_count: int
    metrics: dict[str, float]


@dataclass(frozen=True, slots=True)
class RegressionDecision:
    passed: bool
    establishes_baseline: bool
    failures: tuple[str, ...]


def aggregate_runs(
    profile: PerformanceProfile,
    summaries: Sequence[Mapping[str, Any]],
) -> PerformanceAggregate:
    """Return the median of each release metric across benchmark summaries."""
    if not summaries:
        raise ValueError("at least one benchmark summary is required")
    per_run = tuple(_extract_metrics(summary) for summary in summaries)
    names = per_run[0]
    return PerformanceAggregate(
        profile=profile,
        run_count=len(per_run),
        metrics={
            name: float(statistics.median(run[name] for run in per_run)) for name in names
        },
    )


def evaluate_regression(
    current: PerformanceAggregate,
    baseline: PerformanceAggregate | None,
    policy: RegressionPolicy,
    *,
    k6_passed: bool,
    autoscaling_passed: bool,
) -> RegressionDecision:
    """Evaluate fixed gates and, when present, an identical-profile baseline."""
    _validate_metrics(current.metrics, "current")
    if baseline is not None:
        _validate_metrics(baseline.metrics, "baseline")
    failures: list[str] = []
    if not k6_passed:
        failures.append("k6 gate failed")
    if not autoscaling_passed:
        failures.append("autoscaling gate failed")

    if baseline is not None:
        _require_same_profile(current.profile, baseline.profile)
        throughput_loss = _percent_change(
            baseline.metrics["throughputRps"], current.metrics["throughputRps"], loss=True
        )
        if throughput_loss > policy.throughput_max_loss_percent:
            failures.append(
                f"throughput loss {throughput_loss:.2f}% exceeds "
                f"{policy.throughput_max_loss_percent:.2f}%"
            )
        p95_increase = _percent_change(
            baseline.metrics["latencyP95Ms"], current.metrics["latencyP95Ms"]
        )
        if p95_increase > policy.p95_max_increase_percent:
            failures.append(
                f"p95 increase {p95_increase:.2f}% exceeds "
                f"{policy.p95_max_increase_percent:.2f}%"
            )

    error_rate = current.metrics["errorRate"]
    if error_rate > policy.error_rate_max:
        failures.append(
            f"error rate {error_rate:.6f} exceeds {policy.error_rate_max:.6f}"
        )
    passed = not failures
    return RegressionDecision(
        passed=passed,
        establishes_baseline=passed and baseline is None,
        failures=tuple(failures),
    )


def build_release_record(
    *,
    version: str,
    source_commit: str,
    image_digests: Mapping[str, str],
    aggregate: PerformanceAggregate,
    policy: RegressionPolicy,
) -> dict[str, Any]:
    """Build the compact record that final release attestation may publish later."""
    return {
        "schemaVersion": 1,
        "version": version,
        "sourceCommit": source_commit,
        "imageDigests": dict(sorted(image_digests.items())),
        "profile": _profile_dict(aggregate.profile),
        "runCount": aggregate.run_count,
        "thresholds": {
            "throughputMaxLossPercent": policy.throughput_max_loss_percent,
            "p95MaxIncreasePercent": policy.p95_max_increase_percent,
            "errorRateMax": policy.error_rate_max,
        },
        "aggregates": dict(sorted(aggregate.metrics.items())),
    }


def render_release_record(record: Mapping[str, Any]) -> str:
    """Render release evidence without writing it to the published history."""
    return json.dumps(record, indent=2, sort_keys=True) + "\n"


def newest_comparable_record(
    records: Sequence[Mapping[str, Any]],
    profile: PerformanceProfile,
) -> Mapping[str, Any] | None:
    """Return the latest record in the exact same performance series."""
    identity = _profile_dict(profile)
    comparable = (record for record in records if record.get("profile") == identity)
    return max(comparable, key=lambda record: _version_key(str(record["version"])), default=None)


def render_history(records: Sequence[Mapping[str, Any]]) -> str:
    """Render the human-readable release table in semantic-version order."""
    lines = [
        "# Release performance history",
        "",
        "Comparable Azure AMD64 native results. Raw evidence remains in each release run.",
        "",
        "| Version | Profile | Runs | Throughput (req/s) | Error rate | p95 (ms) | Peak replicas |",
        "| --- | --- | ---: | ---: | ---: | ---: | ---: |",
    ]
    for record in sorted(records, key=lambda item: _version_key(str(item["version"]))):
        metrics = record["aggregates"]
        profile = record["profile"]
        lines.append(
            f"| {record['version']} | {profile['name']} | {record['runCount']} | "
            f"{float(metrics['throughputRps']):.3f} | "
            f"{float(metrics['errorRate']):.6f} | "
            f"{float(metrics['latencyP95Ms']):.3f} | "
            f"{float(metrics['peakReplicas']):.0f} |"
        )
    return "\n".join(lines) + "\n"


def _extract_metrics(summary: Mapping[str, Any]) -> dict[str, float]:
    k6 = _mapping(summary, "k6")
    prometheus = _mapping(summary, "prometheus")
    autoscaling = _mapping(summary, "autoscaling")
    queue_count = _prometheus_value(prometheus, "function_queue_wait_count", "delta")
    queue_sum = _prometheus_value(prometheus, "function_queue_wait_sum", "delta")
    if queue_count == 0 and queue_sum != 0:
        raise ValueError("function_queue_wait_sum.delta must be zero when count is zero")
    metrics = {
        "throughputRps": _k6_value(k6, "http_reqs", "rate"),
        "errorRate": _k6_value(k6, "http_req_failed", "rate", "value"),
        "latencyP50Ms": _k6_value(k6, "http_req_duration", "p(50)", "med"),
        "latencyP95Ms": _k6_value(k6, "http_req_duration", "p(95)"),
        "latencyP99Ms": _k6_value(k6, "http_req_duration", "p(99)"),
        "queueWaitSeconds": queue_sum / queue_count if queue_count else 0.0,
        "coldStarts": _prometheus_value(prometheus, "function_cold_start_total", "delta"),
        "controlPlaneCpuPeak": _prometheus_value(prometheus, "process_cpu_usage", "max"),
        "controlPlaneHeapPeakBytes": _prometheus_value(
            prometheus, "jvm_heap_used_bytes", "max"
        ),
        "peakReplicas": _number(autoscaling, "max_replicas_observed"),
    }
    _validate_metrics(metrics, "summary")
    return metrics


def _k6_value(k6: Mapping[str, Any], metric: str, *names: str) -> float:
    entry = _mapping(k6, metric)
    values = entry.get("values", entry)
    if not isinstance(values, Mapping):
        raise ValueError(f"k6.{metric} must be a mapping")
    for name in names:
        if name in values:
            return _number(values, name)
    raise ValueError(f"k6.{metric} is missing {' or '.join(names)}")


def _prometheus_value(prometheus: Mapping[str, Any], metric: str, name: str) -> float:
    return _number(_mapping(prometheus, metric), name)


def _mapping(parent: Mapping[str, Any], name: str) -> Mapping[str, Any]:
    value = parent.get(name)
    if not isinstance(value, Mapping):
        raise ValueError(f"{name} must be a mapping")
    return value


def _number(parent: Mapping[str, Any], name: str) -> float:
    value = parent.get(name)
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise ValueError(f"{name} must be a finite nonnegative number")
    number = float(value)
    if not math.isfinite(number) or number < 0:
        raise ValueError(f"{name} must be a finite nonnegative number")
    return number


def _validate_metrics(metrics: Mapping[str, object], label: str) -> None:
    for name, value in metrics.items():
        if (
            not isinstance(value, (int, float))
            or isinstance(value, bool)
            or not math.isfinite(float(value))
            or value < 0
        ):
            raise ValueError(f"{label} metric {name} must be a finite nonnegative number")
    error_rate = metrics.get("errorRate")
    if isinstance(error_rate, (int, float)) and error_rate > 1:
        raise ValueError(f"{label} metric errorRate must be between 0 and 1")


def _require_same_profile(current: PerformanceProfile, baseline: PerformanceProfile) -> None:
    mismatches = [
        field.name
        for field in fields(PerformanceProfile)
        if getattr(current, field.name) != getattr(baseline, field.name)
    ]
    if mismatches:
        raise ValueError(f"performance profile mismatch: {', '.join(mismatches)}")


def _percent_change(baseline: float, current: float, *, loss: bool = False) -> float:
    if baseline == 0:
        if loss or current == 0:
            return 0.0
        raise ValueError("cannot calculate percent increase from a zero baseline")
    change = baseline - current if loss else current - baseline
    return max(0.0, change / baseline * 100)


def _profile_dict(profile: PerformanceProfile) -> dict[str, str]:
    values = asdict(profile)
    values["stackVm"] = values.pop("stack_vm")
    values["loadgenVm"] = values.pop("loadgen_vm")
    return values


def _version_key(version: str) -> tuple[int, int, int]:
    plain = version.removeprefix("v")
    try:
        major, minor, patch = plain.split(".")
        return int(major), int(minor), int(patch)
    except (ValueError, TypeError) as error:
        raise ValueError(f"invalid release record version: {version!r}") from error
