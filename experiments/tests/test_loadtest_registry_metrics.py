from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))

from loadtest_registry_metrics import (
    build_prom_queries,
    compute_avg_ms,
    dedup_by_function,
    merge_prom_with_snapshots,
    summarize_control_plane_samples,
    summarize_control_plane_samples_by_windows,
)


def test_dedup_by_function_wraps_expression_with_instance_max_then_function_sum():
    assert (
        dedup_by_function("function_dispatch_total")
        == "sum by (function) (max by (function, instance) (function_dispatch_total))"
    )


def test_build_prom_queries_uses_deduplicated_expressions():
    queries = build_prom_queries(
        "function_latency_ms_seconds",
        "function_e2e_latency_ms_seconds",
        "function_queue_wait_ms_seconds",
        "function_init_duration_ms_seconds",
    )
    assert (
        queries["dispatch"]
        == "sum by (function) (max by (function, instance) (function_dispatch_total))"
    )
    assert (
        queries["latency_p95"]
        == 'sum by (function) (max by (function, instance) (function_latency_ms_seconds{quantile="0.95"}))'
    )
    assert (
        queries["queue_wait_sum"]
        == "sum by (function) (max by (function, instance) (function_queue_wait_ms_seconds_sum))"
    )


def test_compute_avg_ms_from_sum_seconds_and_count():
    assert compute_avg_ms(209.465, 8858) == 23.65


def test_summarize_control_plane_samples_parses_cpu_and_memory():
    samples = [
        "nanofaas-control-plane-abc 10m 500Mi",
        "nanofaas-control-plane-abc 25m 512Mi",
        "nanofaas-control-plane-abc 15m 520Mi",
        "nanofaas-control-plane-abc 30m 530Mi",
    ]

    stats = summarize_control_plane_samples(samples)

    assert stats["samples"] == 4
    assert stats["cpu_avg_m"] == 20.0
    assert stats["cpu_p95_m"] == 30.0
    assert stats["cpu_max_m"] == 30.0
    assert stats["mem_max_bytes"] == 530 * 1024 * 1024


def test_summarize_control_plane_samples_by_windows_groups_by_function():
    samples = [
        "100 fn-control-plane 10m 400Mi",
        "105 fn-control-plane 20m 420Mi",
        "120 fn-control-plane 30m 500Mi",
        "126 fn-control-plane 40m 700Mi",
    ]
    windows = [
        {"function": "word-stats-java", "start": 99, "end": 110},
        {"function": "json-transform-java", "start": 119, "end": 127},
    ]

    grouped = summarize_control_plane_samples_by_windows(samples, windows)

    assert grouped["word-stats-java"]["samples"] == 2
    assert grouped["word-stats-java"]["cpu_avg_m"] == 15.0
    assert grouped["json-transform-java"]["samples"] == 2
    assert grouped["json-transform-java"]["mem_max_bytes"] == 700 * 1024 * 1024


def test_merge_prom_with_snapshots_fills_missing_quantiles():
    prom = {
        "word-stats-java": {"latency_p50": 0.0, "latency_p95": 0.0, "latency_count": 10.0},
    }
    snapshots = [
        {
            "function": "word-stats-java",
            "metrics": {"latency_p50": 0.003, "latency_p95": 0.006, "e2e_p50": 0.0},
        }
    ]

    merged = merge_prom_with_snapshots(prom, snapshots)

    assert merged["word-stats-java"]["latency_p50"] == 0.003
    assert merged["word-stats-java"]["latency_p95"] == 0.006
