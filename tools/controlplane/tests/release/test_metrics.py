from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path
from typing import Any, cast

import pytest
import yaml

from controlplane_tool.release.metrics import (
    PerformanceProfile,
    RegressionPolicy,
    aggregate_runs,
    build_release_record,
    evaluate_regression,
    newest_comparable_record,
    render_history,
    render_release_record,
)


REPO_ROOT = Path(__file__).resolve().parents[4]
PROFILE = PerformanceProfile(
    name="azure-d8s-v5+d2s-v5-amd64-native-loadtest-v1",
    provider="azure",
    stack_vm="Standard_D8s_v5",
    loadgen_vm="Standard_D2s_v5",
    architecture="amd64",
    flavor="native",
    scenario="scenarios-v2/loadtest.yaml",
)
POLICY = RegressionPolicy(
    throughput_max_loss_percent=10,
    p95_max_increase_percent=15,
    error_rate_max=0.30,
)


def _summary(value: float) -> dict[str, object]:
    return {
        "k6": {
            "http_reqs": {"values": {"rate": value}},
            "http_req_failed": {"values": {"rate": value / 1000}},
            "http_req_duration": {
                "values": {
                    "p(50)": value + 1,
                    "p(95)": value + 2,
                    "p(99)": value + 3,
                }
            },
        },
        "prometheus": {
            "function_queue_wait_count": {"delta": 10},
            "function_queue_wait_sum": {"delta": value * 10},
            "function_cold_start_total": {"delta": value + 4},
            "process_cpu_usage": {"max": value / 100},
            "jvm_heap_used_bytes": {"max": value * 1024},
        },
        "autoscaling": {
            "max_replicas_observed": int(value) + 5,
            "final_desired_replicas": 0,
        },
    }


def test_aggregate_runs_uses_per_metric_median() -> None:
    aggregate = aggregate_runs(PROFILE, (_summary(30), _summary(10), _summary(20)))

    assert aggregate.run_count == 3
    assert aggregate.metrics == {
        "throughputRps": 20.0,
        "errorRate": 0.02,
        "latencyP50Ms": 21.0,
        "latencyP95Ms": 22.0,
        "latencyP99Ms": 23.0,
        "queueWaitSeconds": 20.0,
        "coldStarts": 24.0,
        "controlPlaneCpuPeak": 0.2,
        "controlPlaneHeapPeakBytes": 20480.0,
        "peakReplicas": 25.0,
    }


@pytest.mark.parametrize("invalid", (float("nan"), float("inf"), float("-inf"), -1.0))
def test_aggregate_runs_rejects_invalid_source_numbers(invalid: float) -> None:
    summary = cast(dict[str, Any], _summary(20))
    summary["k6"]["http_reqs"]["values"]["rate"] = invalid

    with pytest.raises(ValueError, match="finite nonnegative"):
        aggregate_runs(PROFILE, (summary,) * 3)


def test_aggregate_runs_rejects_error_rate_above_one() -> None:
    summary = cast(dict[str, Any], _summary(20))
    summary["k6"]["http_req_failed"]["values"]["rate"] = 1.01

    with pytest.raises(ValueError, match="errorRate.*between 0 and 1"):
        aggregate_runs(PROFILE, (summary,) * 3)


def test_aggregate_runs_preserves_zero_queue_wait() -> None:
    summary = cast(dict[str, Any], _summary(0))
    summary["prometheus"]["function_queue_wait_count"]["delta"] = 0
    summary["prometheus"]["function_queue_wait_sum"]["delta"] = 0

    aggregate = aggregate_runs(PROFILE, (summary,) * 3)

    assert aggregate.metrics["queueWaitSeconds"] == 0


@pytest.mark.parametrize(
    ("field", "value"),
    (
        ("provider", "multipass"),
        ("stack_vm", "Standard_D4s_v5"),
        ("loadgen_vm", "Standard_D4s_v5"),
        ("architecture", "arm64"),
        ("flavor", "jvm"),
        ("scenario", "scenarios-v2/other.yaml"),
    ),
)
def test_regression_comparison_rejects_different_profiles(field: str, value: str) -> None:
    current = aggregate_runs(PROFILE, (_summary(20),) * 3)
    baseline = replace(current, profile=replace(PROFILE, **{field: value}))

    with pytest.raises(ValueError, match=field):
        evaluate_regression(
            current,
            baseline,
            POLICY,
            k6_passed=True,
            autoscaling_passed=True,
        )


def test_first_passing_release_establishes_baseline() -> None:
    current = aggregate_runs(PROFILE, (_summary(20),) * 3)

    result = evaluate_regression(
        current,
        None,
        POLICY,
        k6_passed=True,
        autoscaling_passed=True,
    )

    assert result.passed is True
    assert result.establishes_baseline is True
    assert result.failures == ()


@pytest.mark.parametrize("evidence", ("current", "baseline"))
@pytest.mark.parametrize("invalid", (float("nan"), float("inf"), float("-inf"), -1.0))
def test_regression_rejects_invalid_current_and_baseline_evidence(
    evidence: str,
    invalid: float,
) -> None:
    valid = aggregate_runs(PROFILE, (_summary(20),) * 3)
    malformed = replace(
        valid,
        metrics={**valid.metrics, "throughputRps": invalid},
    )
    current, baseline = (malformed, valid) if evidence == "current" else (valid, malformed)

    with pytest.raises(ValueError, match=f"{evidence}.*throughputRps.*finite nonnegative"):
        evaluate_regression(
            current,
            baseline,
            POLICY,
            k6_passed=True,
            autoscaling_passed=True,
        )


@pytest.mark.parametrize("evidence", ("current", "baseline"))
def test_regression_rejects_out_of_domain_error_rate_evidence(evidence: str) -> None:
    valid = aggregate_runs(PROFILE, (_summary(20),) * 3)
    malformed = replace(valid, metrics={**valid.metrics, "errorRate": 1.01})
    current, baseline = (malformed, valid) if evidence == "current" else (valid, malformed)

    with pytest.raises(ValueError, match=f"{evidence}.*errorRate.*between 0 and 1"):
        evaluate_regression(
            current,
            baseline,
            POLICY,
            k6_passed=True,
            autoscaling_passed=True,
        )


def test_regression_preserves_valid_zero_evidence() -> None:
    aggregate = aggregate_runs(PROFILE, (_summary(20),) * 3)
    zero = replace(aggregate, metrics={name: 0.0 for name in aggregate.metrics})

    result = evaluate_regression(
        zero,
        zero,
        POLICY,
        k6_passed=True,
        autoscaling_passed=True,
    )

    assert result.passed is True
    assert result.failures == ()


def test_regression_evaluation_requires_explicit_gate_evidence() -> None:
    current = aggregate_runs(PROFILE, (_summary(20),) * 3)

    with pytest.raises(TypeError):
        evaluate_regression(current, None, POLICY)  # type: ignore[call-arg]


@pytest.mark.parametrize(
    ("k6_passed", "autoscaling_passed", "message"),
    ((False, True, "k6"), (True, False, "autoscaling")),
)
def test_initial_baseline_requires_existing_gates(
    k6_passed: bool,
    autoscaling_passed: bool,
    message: str,
) -> None:
    current = aggregate_runs(PROFILE, (_summary(20),) * 3)

    result = evaluate_regression(
        current,
        None,
        POLICY,
        k6_passed=k6_passed,
        autoscaling_passed=autoscaling_passed,
    )

    assert result.passed is False
    assert result.establishes_baseline is False
    assert any(message in failure for failure in result.failures)


def test_regression_policy_checks_throughput_p95_and_error_rate() -> None:
    baseline = aggregate_runs(PROFILE, (_summary(100),) * 3)
    current = aggregate_runs(PROFILE, (_summary(80),) * 3)
    current.metrics["errorRate"] = 0.31

    result = evaluate_regression(
        current,
        baseline,
        POLICY,
        k6_passed=True,
        autoscaling_passed=True,
    )

    assert result.passed is False
    assert result.establishes_baseline is False
    assert result.failures == (
        "throughput loss 20.00% exceeds 10.00%",
        "error rate 0.310000 exceeds 0.300000",
    )

    current.metrics["throughputRps"] = 100.0
    current.metrics["errorRate"] = 0.0
    current.metrics["latencyP95Ms"] = 118.0
    assert evaluate_regression(
        current,
        baseline,
        POLICY,
        k6_passed=True,
        autoscaling_passed=True,
    ).failures == (
        "p95 increase 15.69% exceeds 15.00%",
    )


def test_release_configuration_owns_the_versioned_policy() -> None:
    config = yaml.safe_load((REPO_ROOT / "tools/controlplane/release.yaml").read_text())

    assert config == {
        "schemaVersion": 1,
        "build": {"maxParallelism": 4},
        "benchmark": {
            "scenario": "scenarios-v2/loadtest.yaml",
            "runs": 3,
            "profile": "azure-d8s-v5+d2s-v5-amd64-native-loadtest-v1",
            "regression": {
                "throughputMaxLossPercent": 10,
                "p95MaxIncreasePercent": 15,
                "errorRateMax": 0.30,
            },
        },
    }


def test_record_and_history_rendering_are_deterministic_and_pure(tmp_path: Path) -> None:
    aggregate = aggregate_runs(PROFILE, (_summary(30), _summary(10), _summary(20)))
    record = build_release_record(
        version="v0.18.0",
        source_commit="abc123",
        image_digests={"warm-echo": "sha256:2", "control-plane": "sha256:1"},
        aggregate=aggregate,
        policy=POLICY,
    )

    rendered = render_release_record(record)
    assert rendered == render_release_record(record)
    assert json.loads(rendered)["imageDigests"] == {
        "control-plane": "sha256:1",
        "warm-echo": "sha256:2",
    }
    assert rendered.endswith("\n")

    history = render_history((record,))
    assert history == render_history((record,))
    assert "| v0.18.0 |" in history
    assert "20.000" in history
    assert list(tmp_path.iterdir()) == []


def test_newest_comparable_record_ignores_other_performance_series() -> None:
    aggregate = aggregate_runs(PROFILE, (_summary(20),) * 3)

    def record(version: str, profile: PerformanceProfile = PROFILE) -> dict[str, object]:
        selected = replace(aggregate, profile=profile)
        return build_release_record(
            version=version,
            source_commit=version,
            image_digests={},
            aggregate=selected,
            policy=POLICY,
        )

    records = (
        record("v0.19.0", replace(PROFILE, architecture="arm64")),
        record("v0.17.0"),
        record("v0.18.0"),
    )

    assert newest_comparable_record(records, PROFILE)["version"] == "v0.18.0"
    assert newest_comparable_record(records, replace(PROFILE, provider="proxmox")) is None
