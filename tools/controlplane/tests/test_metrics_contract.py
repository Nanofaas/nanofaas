"""
Tests for metrics_contract.py — CORE_REQUIRED_METRICS and LEGACY_STRICT_REQUIRED_METRICS constants.
"""
from __future__ import annotations

from controlplane_tool.loadtest.metrics_contract import (
    CORE_REQUIRED_METRICS,
    LEGACY_STRICT_REQUIRED_METRICS,
)


def test_core_required_metrics_is_non_empty() -> None:
    assert len(CORE_REQUIRED_METRICS) > 0


def test_core_required_metrics_includes_dispatch_total() -> None:
    assert "function_dispatch_total" in CORE_REQUIRED_METRICS


def test_core_required_metrics_includes_latency() -> None:
    assert "function_latency_ms" in CORE_REQUIRED_METRICS


def test_core_required_metrics_all_start_with_function_prefix() -> None:
    assert all(m.startswith("function_") for m in CORE_REQUIRED_METRICS)


def test_legacy_strict_required_metrics_is_superset_of_core() -> None:
    core_set = set(CORE_REQUIRED_METRICS)
    legacy_set = set(LEGACY_STRICT_REQUIRED_METRICS)
    assert core_set.issubset(legacy_set)


def test_legacy_strict_required_metrics_includes_enqueue_total() -> None:
    assert "function_enqueue_total" in LEGACY_STRICT_REQUIRED_METRICS


def test_core_required_metrics_contains_no_duplicates() -> None:
    assert len(CORE_REQUIRED_METRICS) == len(set(CORE_REQUIRED_METRICS))


def test_legacy_strict_required_metrics_contains_no_duplicates() -> None:
    assert len(LEGACY_STRICT_REQUIRED_METRICS) == len(set(LEGACY_STRICT_REQUIRED_METRICS))
