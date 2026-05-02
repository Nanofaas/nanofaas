"""
Tests for adapters.py facade — ShellCommandAdapter delegation.

Verifies that every public method delegates to the correct composed object
(GradleOps, K6Ops, LoadtestBootstrap, evaluate_metrics_gate).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

import pytest

from controlplane_tool.adapters import AdapterResult, ShellCommandAdapter
from controlplane_tool.gradle_ops import CommandResult
from controlplane_tool.loadtest_bootstrap import LoadtestBootstrapContext
from controlplane_tool.loadtest_catalog import resolve_load_profile
from controlplane_tool.loadtest_models import LoadtestRequest, MetricsGate
from controlplane_tool.models import ControlPlaneConfig, MetricsConfig, Profile, TestsConfig
from controlplane_tool.scenario_loader import load_scenario_file


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _profile() -> Profile:
    return Profile(
        name="qa",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="jvm"),
        modules=[],
        tests=TestsConfig(enabled=True, metrics=True),
        metrics=MetricsConfig(required=["function_dispatch_total"]),
    )


def _make_request() -> LoadtestRequest:
    return LoadtestRequest(
        name="test",
        profile=_profile(),
        scenario=load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml")),
        load_profile=resolve_load_profile("quick"),
        metrics_gate=MetricsGate(required_metrics=["function_dispatch_total"]),
    )


@dataclass
class FakeContext:
    base_url: str = "http://127.0.0.1:8080"
    prometheus_url: str = "http://127.0.0.1:9090"
    scenario_manifest_path: Path = Path("/tmp/manifest.json")
    target_functions: list = field(default_factory=lambda: ["echo-test"])
    target_results: list = field(default_factory=list)
    started_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    prometheus_session: object = None


# ---------------------------------------------------------------------------
# Construction
# ---------------------------------------------------------------------------

def test_adapter_constructs_with_no_args(tmp_path: Path) -> None:
    # Should not raise; defaults to Path.cwd()
    adapter = ShellCommandAdapter(repo_root=tmp_path)
    assert adapter._gradle is not None
    assert adapter._k6 is not None
    assert adapter._bootstrap is not None


def test_adapter_result_is_alias_for_command_result() -> None:
    r = AdapterResult(ok=True, detail="ok")
    assert isinstance(r, CommandResult)


# ---------------------------------------------------------------------------
# Delegation — GradleOps
# ---------------------------------------------------------------------------

def test_preflight_delegates_to_gradle(tmp_path: Path, monkeypatch) -> None:
    adapter = ShellCommandAdapter(repo_root=tmp_path)
    called = []
    monkeypatch.setattr(adapter._gradle, "preflight_missing", lambda p: called.append(p) or [])
    result = adapter.preflight(_profile())
    assert len(called) == 1
    assert result == []


def test_compile_delegates_to_gradle(tmp_path: Path, monkeypatch) -> None:
    adapter = ShellCommandAdapter(repo_root=tmp_path)
    monkeypatch.setattr(adapter._gradle, "compile", lambda p, d: (True, "compiled"))
    ok, detail = adapter.compile(_profile(), tmp_path)
    assert ok is True
    assert "compiled" in detail


def test_build_image_delegates_to_gradle(tmp_path: Path, monkeypatch) -> None:
    adapter = ShellCommandAdapter(repo_root=tmp_path)
    monkeypatch.setattr(adapter._gradle, "build_image", lambda p, d: (True, "image=qa:latest"))
    ok, detail = adapter.build_image(_profile(), tmp_path)
    assert ok is True
    assert "image" in detail


def test_run_api_tests_delegates_to_gradle(tmp_path: Path, monkeypatch) -> None:
    adapter = ShellCommandAdapter(repo_root=tmp_path)
    monkeypatch.setattr(adapter._gradle, "run_api_tests", lambda p, d: (True, "api tests passed"))
    ok, detail = adapter.run_api_tests(_profile(), tmp_path)
    assert ok is True


def test_run_mockk8s_tests_delegates_to_gradle(tmp_path: Path, monkeypatch) -> None:
    adapter = ShellCommandAdapter(repo_root=tmp_path)
    monkeypatch.setattr(adapter._gradle, "run_mockk8s_tests", lambda p, d: (True, "mockk8s tests passed"))
    ok, detail = adapter.run_mockk8s_tests(_profile(), tmp_path)
    assert ok is True


# ---------------------------------------------------------------------------
# Delegation — LoadtestBootstrap
# ---------------------------------------------------------------------------

def test_bootstrap_loadtest_delegates_to_bootstrap(tmp_path: Path, monkeypatch) -> None:
    adapter = ShellCommandAdapter(repo_root=tmp_path)
    fake_ctx = FakeContext()
    monkeypatch.setattr(adapter._bootstrap, "bootstrap", lambda p, r, d: fake_ctx)
    result = adapter.bootstrap_loadtest(_profile(), _make_request(), tmp_path)
    assert result is fake_ctx


def test_cleanup_loadtest_delegates_to_bootstrap(tmp_path: Path, monkeypatch) -> None:
    adapter = ShellCommandAdapter(repo_root=tmp_path)
    cleaned = []
    monkeypatch.setattr(adapter._bootstrap, "cleanup", lambda ctx: cleaned.append(ctx))
    ctx = FakeContext()
    adapter.cleanup_loadtest(ctx)
    assert len(cleaned) == 1


# ---------------------------------------------------------------------------
# Delegation — K6Ops
# ---------------------------------------------------------------------------

def test_run_loadtest_k6_delegates_to_k6(tmp_path: Path, monkeypatch) -> None:
    adapter = ShellCommandAdapter(repo_root=tmp_path)
    monkeypatch.setattr(adapter._k6, "run_loadtest_k6", lambda req, ctx, d: (True, "k6 ok"))
    ok, detail = adapter.run_loadtest_k6(_make_request(), FakeContext(), tmp_path)
    assert ok is True
    assert "k6" in detail


# ---------------------------------------------------------------------------
# Delegation — evaluate_metrics_gate
# ---------------------------------------------------------------------------

def test_evaluate_metrics_gate_delegates_to_gate_module(tmp_path: Path, monkeypatch) -> None:
    adapter = ShellCommandAdapter(repo_root=tmp_path)
    monkeypatch.setattr(
        "controlplane_tool.adapters.evaluate_metrics_gate",
        lambda p, r, ctx, d: (True, "prometheus checks passed"),
    )
    ok, detail = adapter.evaluate_metrics_gate(_profile(), _make_request(), FakeContext(), tmp_path)
    assert ok is True
    assert "prometheus" in detail


# ---------------------------------------------------------------------------
# run_metrics_tests — combined legacy method
# ---------------------------------------------------------------------------

def test_run_metrics_tests_returns_ok_when_all_pass(tmp_path: Path, monkeypatch) -> None:
    adapter = ShellCommandAdapter(repo_root=tmp_path)
    fake_ctx = FakeContext()
    monkeypatch.setattr(adapter._gradle, "run_api_tests", lambda p, d: (True, "ok"))
    monkeypatch.setattr(adapter._bootstrap, "legacy_loadtest_request", lambda p: _make_request())
    monkeypatch.setattr(adapter._bootstrap, "bootstrap", lambda p, r, d: fake_ctx)
    monkeypatch.setattr(adapter._bootstrap, "cleanup", lambda ctx: None)
    monkeypatch.setattr(adapter._k6, "run_loadtest_k6", lambda r, ctx, d: (True, "k6 ok"))
    monkeypatch.setattr(
        "controlplane_tool.adapters.evaluate_metrics_gate",
        lambda p, r, ctx, d: (True, "prometheus checks passed"),
    )
    ok, detail = adapter.run_metrics_tests(_profile(), tmp_path)
    assert ok is True
    assert "prometheus" in detail


def test_run_metrics_tests_short_circuits_when_api_tests_fail(tmp_path: Path, monkeypatch) -> None:
    adapter = ShellCommandAdapter(repo_root=tmp_path)
    monkeypatch.setattr(adapter._gradle, "run_api_tests", lambda p, d: (False, "test failure"))
    ok, detail = adapter.run_metrics_tests(_profile(), tmp_path)
    assert ok is False
    assert "test failure" in detail


def test_run_metrics_tests_short_circuits_when_bootstrap_fails(tmp_path: Path, monkeypatch) -> None:
    adapter = ShellCommandAdapter(repo_root=tmp_path)
    monkeypatch.setattr(adapter._gradle, "run_api_tests", lambda p, d: (True, "ok"))
    monkeypatch.setattr(adapter._bootstrap, "legacy_loadtest_request", lambda p: _make_request())
    monkeypatch.setattr(
        adapter._bootstrap, "bootstrap",
        lambda p, r, d: (_ for _ in ()).throw(RuntimeError("port in use")),
    )
    ok, detail = adapter.run_metrics_tests(_profile(), tmp_path)
    assert ok is False
    assert "port in use" in detail


def test_run_metrics_tests_returns_failure_when_gate_fails(tmp_path: Path, monkeypatch) -> None:
    adapter = ShellCommandAdapter(repo_root=tmp_path)
    fake_ctx = FakeContext()
    monkeypatch.setattr(adapter._gradle, "run_api_tests", lambda p, d: (True, "ok"))
    monkeypatch.setattr(adapter._bootstrap, "legacy_loadtest_request", lambda p: _make_request())
    monkeypatch.setattr(adapter._bootstrap, "bootstrap", lambda p, r, d: fake_ctx)
    monkeypatch.setattr(adapter._bootstrap, "cleanup", lambda ctx: None)
    monkeypatch.setattr(adapter._k6, "run_loadtest_k6", lambda r, ctx, d: (True, "k6 ok"))
    monkeypatch.setattr(
        "controlplane_tool.adapters.evaluate_metrics_gate",
        lambda p, r, ctx, d: (False, "missing required metrics"),
    )
    ok, detail = adapter.run_metrics_tests(_profile(), tmp_path)
    assert ok is False
    assert "missing required metrics" in detail


def test_run_metrics_tests_skips_k6_gracefully(tmp_path: Path, monkeypatch) -> None:
    adapter = ShellCommandAdapter(repo_root=tmp_path)
    fake_ctx = FakeContext()
    monkeypatch.setattr(adapter._gradle, "run_api_tests", lambda p, d: (True, "ok"))
    monkeypatch.setattr(adapter._bootstrap, "legacy_loadtest_request", lambda p: _make_request())
    monkeypatch.setattr(adapter._bootstrap, "bootstrap", lambda p, r, d: fake_ctx)
    monkeypatch.setattr(adapter._bootstrap, "cleanup", lambda ctx: None)
    monkeypatch.setattr(adapter._k6, "run_loadtest_k6", lambda r, ctx, d: (True, "skipped"))
    monkeypatch.setattr(
        "controlplane_tool.adapters.evaluate_metrics_gate",
        lambda p, r, ctx, d: (True, "prometheus checks passed"),
    )
    ok, detail = adapter.run_metrics_tests(_profile(), tmp_path)
    assert ok is True
    assert "skipped" in detail
