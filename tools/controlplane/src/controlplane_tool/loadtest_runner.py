from __future__ import annotations

from pathlib import Path
from typing import Callable

from controlplane_tool.adapters import ShellCommandAdapter
from controlplane_tool.loadtest_flows import build_loadtest_flow
from controlplane_tool.loadtest_models import LoadtestRequest
from controlplane_tool.loadtest_tasks import LoadtestStepEvent
from controlplane_tool.prefect_runtime import run_local_flow
from controlplane_tool.run_models import RunResult


class LoadtestRunner:
    def __init__(self, adapter: object | None = None) -> None:
        self.adapter = adapter or ShellCommandAdapter()

    def run(
        self,
        request: LoadtestRequest,
        runs_root: Path | None = None,
        *,
        event_listener: Callable[[LoadtestStepEvent], None] | None = None,
    ) -> RunResult:
        flow = build_loadtest_flow(
            request.load_profile.name,
            request=request,
            adapter=self.adapter,
            runs_root=runs_root,
            event_listener=event_listener,
        )
        flow_result = run_local_flow(flow.flow_id, flow.run)
        if flow_result.status != "completed" or flow_result.result is None:
            raise RuntimeError(flow_result.error or "loadtest flow failed")
        return flow_result.result
