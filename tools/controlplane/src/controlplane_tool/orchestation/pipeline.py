from __future__ import annotations

from pathlib import Path

from controlplane_tool.orchestation.adapters import ShellCommandAdapter
from controlplane_tool.orchestation.flow_catalog import resolve_flow_definition
from controlplane_tool.core.models import Profile
from controlplane_tool.orchestation.prefect_runtime import run_local_flow
from controlplane_tool.core.run_models import RunResult


class PipelineRunner:
    def __init__(self, adapter: object | None = None) -> None:
        self.adapter = adapter or ShellCommandAdapter()

    def run(self, profile: Profile, runs_root: Path | None = None) -> RunResult:
        flow = resolve_flow_definition(
            "infra.pipeline",
            profile=profile,
            adapter=self.adapter,
            runs_root=runs_root,
        )
        flow_result = run_local_flow(flow.flow_id, flow.run)
        if flow_result.status != "completed" or flow_result.result is None:
            raise RuntimeError(flow_result.error or "pipeline flow failed")
        return flow_result.result


def execute_pipeline(
    profile: Profile,
    runner: PipelineRunner | None = None,
    runs_root: Path | None = None,
) -> RunResult:
    active_runner = runner or PipelineRunner()
    if runs_root is None:
        return active_runner.run(profile)
    return active_runner.run(profile, runs_root=runs_root)
