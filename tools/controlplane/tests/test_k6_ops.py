"""
Tests for k6_ops — K6Ops.run_loadtest_k6 and stage arg helpers.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from controlplane_tool.building.gradle_ops import CommandResult
from controlplane_tool.loadtest.k6_ops import K6Ops
from controlplane_tool.loadtest.loadtest_catalog import resolve_load_profile
from controlplane_tool.loadtest.loadtest_models import LoadtestRequest, MetricsGate
from controlplane_tool.core.models import ControlPlaneConfig, Profile, TestsConfig
from controlplane_tool.scenario.scenario_loader import load_scenario_file


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

def _profile() -> Profile:
    return Profile(
        name="qa",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="jvm"),
        modules=[],
        tests=TestsConfig(enabled=True, metrics=True),
    )


@dataclass
class FakeContext:
    base_url: str = "http://127.0.0.1:8080"
    prometheus_url: str = "http://127.0.0.1:9090"
    scenario_manifest_path: Path = Path("/tmp/manifest.json")
    target_functions: list = None  # type: ignore[assignment]
    target_results: list = None  # type: ignore[assignment]
    started_at: datetime = None  # type: ignore[assignment]

    def __post_init__(self) -> None:
        if self.target_functions is None:
            self.target_functions = ["echo-test"]
        if self.target_results is None:
            self.target_results = []
        if self.started_at is None:
            self.started_at = datetime.now(timezone.utc)


# ---------------------------------------------------------------------------
# k6 stage args
# ---------------------------------------------------------------------------

def test_k6_stage_args_formats_stages_correctly(tmp_path: Path) -> None:
    ops = K6Ops(tmp_path)
    load_profile = resolve_load_profile("quick")
    args = ops._k6_stage_args(load_profile)
    assert "--stage" in args
    assert any(":" in a for a in args)


# ---------------------------------------------------------------------------
# run_loadtest_k6
# ---------------------------------------------------------------------------

def test_k6_run_skipped_when_script_missing(tmp_path: Path) -> None:
    ops = K6Ops(tmp_path)
    request = LoadtestRequest(
        name="test",
        profile=_profile(),
        scenario=load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml")),
        load_profile=resolve_load_profile("quick"),
        metrics_gate=MetricsGate(),
    )
    ctx = FakeContext()
    ok, detail = ops.run_loadtest_k6(request, ctx, tmp_path)
    assert ok is True
    assert "skipped" in detail


def test_k6_run_calls_k6_binary_when_script_present(tmp_path: Path) -> None:
    k6_script = tmp_path / "tools" / "controlplane" / "assets" / "k6" / "tool-metrics-echo.js"
    k6_script.parent.mkdir(parents=True)
    k6_script.write_text("export default function(){}", encoding="utf-8")

    ops = K6Ops(tmp_path)
    request = LoadtestRequest(
        name="test",
        profile=_profile(),
        scenario=load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml")),
        load_profile=resolve_load_profile("quick"),
        metrics_gate=MetricsGate(),
    )
    ctx = FakeContext()

    calls: list[list[str]] = []
    ops._run = lambda cmd, run_dir, log_name: (  # type: ignore[method-assign]
        calls.append(cmd) or CommandResult(ok=True, detail="ok")
    )
    ok, detail = ops.run_loadtest_k6(request, ctx, tmp_path)

    assert ok is True
    assert any(c[0] == "k6" for c in calls)


def test_k6_run_sets_target_results_on_context(tmp_path: Path) -> None:
    k6_script = tmp_path / "tools" / "controlplane" / "assets" / "k6" / "tool-metrics-echo.js"
    k6_script.parent.mkdir(parents=True)
    k6_script.write_text("", encoding="utf-8")

    ops = K6Ops(tmp_path)
    ops._run = lambda cmd, run_dir, log_name: CommandResult(ok=True, detail="ok")  # type: ignore[method-assign]

    request = LoadtestRequest(
        name="test",
        profile=_profile(),
        scenario=load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml")),
        load_profile=resolve_load_profile("quick"),
        metrics_gate=MetricsGate(),
    )
    ctx = FakeContext(target_functions=["fn-a", "fn-b"])
    ops.run_loadtest_k6(request, ctx, tmp_path)

    assert len(ctx.target_results) == 2
    assert all(r.status == "passed" for r in ctx.target_results)


def test_k6_run_fails_when_k6_exits_nonzero(tmp_path: Path) -> None:
    k6_script = tmp_path / "tools" / "controlplane" / "assets" / "k6" / "tool-metrics-echo.js"
    k6_script.parent.mkdir(parents=True)
    k6_script.write_text("", encoding="utf-8")

    ops = K6Ops(tmp_path)
    ops._run = lambda cmd, run_dir, log_name: CommandResult(ok=False, detail="exit=1")  # type: ignore[method-assign]

    request = LoadtestRequest(
        name="test",
        profile=_profile(),
        scenario=load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml")),
        load_profile=resolve_load_profile("quick"),
        metrics_gate=MetricsGate(),
    )
    ctx = FakeContext()
    ok, detail = ops.run_loadtest_k6(request, ctx, tmp_path)
    assert ok is False
    assert "failed" in ctx.target_results[0].status
