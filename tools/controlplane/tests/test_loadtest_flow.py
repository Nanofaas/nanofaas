from __future__ import annotations

from controlplane_tool.scenario.loadtest_flow import FlowPhase, RunContext


def test_run_context_starts_empty_and_is_mutable() -> None:
    ctx = RunContext()
    assert ctx.stack_info is None
    assert ctx.loadgen_info is None
    assert ctx.control_plane_url is None
    assert ctx.prometheus_url is None
    assert ctx.run_dir is None
    assert ctx.remote_paths is None
    assert ctx.stack_host is None
    ctx.stack_host = "10.0.0.5"
    assert ctx.stack_host == "10.0.0.5"


def test_flow_phase_members() -> None:
    assert {p.name for p in FlowPhase} >= {"AFTER_STACK_READY", "BEFORE_LOADGEN"}
