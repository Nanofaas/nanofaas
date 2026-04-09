from __future__ import annotations

from collections.abc import Callable
from datetime import datetime, timezone
from pathlib import Path

from controlplane_tool.adapters import ShellCommandAdapter
from controlplane_tool.loadtest_models import LoadtestRequest
from controlplane_tool.loadtest_tasks import (
    LoadtestStepEvent,
    bootstrap_loadtest_task,
    run_loadtest_step_task,
    write_loadtest_report_task,
)
from controlplane_tool.paths import default_tool_paths
from controlplane_tool.prefect_models import LocalFlowDefinition
from controlplane_tool.run_models import RunResult


def build_loadtest_flow(
    load_profile_name: str,
    *,
    request: LoadtestRequest | None = None,
    adapter: object | None = None,
    runs_root: Path | None = None,
    event_listener: Callable[[LoadtestStepEvent], None] | None = None,
) -> LocalFlowDefinition[RunResult | None]:
    flow_id = f"loadtest.{str(load_profile_name).replace('-', '_')}"
    task_ids = [
        "loadtest.bootstrap",
        "loadtest.execute_k6",
        "metrics.evaluate_gate",
        "loadtest.write_report",
    ]
    if request is None:
        return LocalFlowDefinition(flow_id=flow_id, task_ids=task_ids, run=lambda: None)

    active_adapter = adapter or ShellCommandAdapter()
    root = runs_root or default_tool_paths().runs_dir

    def _run() -> RunResult:
        timestamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
        run_dir = root / f"{timestamp}-{request.name}"
        run_dir.mkdir(parents=True, exist_ok=True)

        bootstrap_result = bootstrap_loadtest_task(
            adapter=active_adapter,
            request=request,
            run_dir=run_dir,
            event_listener=event_listener,
        )
        steps = list(bootstrap_result.steps)
        context = bootstrap_result.context

        if context is None:
            report_step = write_loadtest_report_task(
                request=request,
                run_dir=run_dir,
                steps=steps,
                final_status="failed",
                event_listener=event_listener,
            )
            steps.append(report_step)
            return RunResult(
                profile_name=request.profile.name,
                run_dir=run_dir,
                final_status="failed",
                steps=steps,
            )

        target_results = []
        try:
            load_step = run_loadtest_step_task(
                "load_k6",
                active_adapter.run_loadtest_k6,
                request,
                context,
                run_dir,
                event_listener=event_listener,
            )
            steps.append(load_step)
            target_results = list(getattr(context, "target_results", []))
            gate_step = run_loadtest_step_task(
                "metrics_gate",
                active_adapter.evaluate_metrics_gate,
                request.profile,
                request,
                context,
                run_dir,
                event_listener=event_listener,
            )
            steps.append(gate_step)
            target_results = list(getattr(context, "target_results", target_results))
        finally:
            active_adapter.cleanup_loadtest(context)

        final_status = "failed" if any(step.status == "failed" for step in steps) else "passed"
        report_step = write_loadtest_report_task(
            request=request,
            run_dir=run_dir,
            steps=steps,
            final_status=final_status,
            target_results=target_results,
            event_listener=event_listener,
        )
        steps.append(report_step)
        return RunResult(
            profile_name=request.profile.name,
            run_dir=run_dir,
            final_status=final_status,
            steps=steps,
        )

    return LocalFlowDefinition(flow_id=flow_id, task_ids=task_ids, run=_run)
