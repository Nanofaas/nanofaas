from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))

from loadtest_registry_metrics import (
    build_prom_queries,
    compute_avg_ms,
    dedup_by_function,
    summarize_control_plane_samples,
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
