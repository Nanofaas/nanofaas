"""
tui_workflow_controller.py — Runs live Rich workflows from TUI menu actions.

Owns run_live_workflow, run_shared_flow, and the command-result helpers
that were previously on NanofaasTUI.
"""
from __future__ import annotations

from typing import Any, Callable

from rich.live import Live

from tui_toolkit import bind_workflow_sink
from tui_toolkit.console import console
from controlplane_tool.prefect_runtime import run_local_flow
from controlplane_tool.tui_event_applier import TuiEventApplier
from controlplane_tool.tui_workflow import TuiWorkflowSink, WorkflowDashboard, WorkflowKeyListener


class TuiWorkflowController:
    """Runs flows inside a Rich Live dashboard and routes events to the dashboard."""

    def __init__(self, event_applier: TuiEventApplier) -> None:
        self._applier = event_applier

    def run_live_workflow(
        self,
        *,
        title: str,
        summary_lines: list[str],
        planned_steps: list[str] | None,
        action: Callable[[WorkflowDashboard, TuiWorkflowSink], Any],
    ) -> Any:
        dashboard = WorkflowDashboard(
            title=title,
            breadcrumb=f"Main / {title}",
            footer_hint="l toggle logs | Ctrl+C back",
            summary_lines=summary_lines,
            planned_steps=planned_steps,
        )
        live: Live | None = None

        def _refresh() -> None:
            if live is not None:
                live.update(dashboard.render(), refresh=True)

        sink = TuiWorkflowSink(dashboard, refresh=_refresh)
        key_listener = WorkflowKeyListener(
            lambda key: (dashboard.toggle_logs(), _refresh()) if key.lower() == "l" else None
        )
        with Live(
            dashboard.render(), console=console, refresh_per_second=8, transient=False
        ) as active_live:
            live = active_live
            active_live.update(dashboard.render(), refresh=True)
            key_listener.start()
            try:
                with bind_workflow_sink(sink):
                    result = action(dashboard, sink)
                    _refresh()
                    return result
            finally:
                key_listener.stop()

    def run_shared_flow(
        self,
        flow: Any,
        *,
        allow_none_result: bool = True,
        on_result: Callable[[Any], None] | None = None,
    ) -> Any:
        flow_result = run_local_flow(flow.flow_id, flow.run)
        if flow_result.status != "completed":
            raise RuntimeError(flow_result.error or f"{flow.flow_id} failed")
        if flow_result.result is None and not allow_none_result:
            raise RuntimeError(f"{flow.flow_id} returned no result")
        if on_result is not None:
            on_result(flow_result.result)
        self._raise_on_nonzero_command_result(flow_result.result)
        return flow_result.result

    def append_command_result_logs(self, dashboard: WorkflowDashboard, result: Any) -> None:
        if isinstance(result, list):
            for item in result:
                self.append_command_result_logs(dashboard, item)
            return
        stdout = getattr(result, "stdout", "")
        stderr = getattr(result, "stderr", "")
        if stdout:
            dashboard.append_log(str(stdout).strip())
        if stderr:
            dashboard.append_log(str(stderr).strip())

    def _raise_on_nonzero_command_result(self, result: Any) -> None:
        if isinstance(result, list):
            for item in result:
                self._raise_on_nonzero_command_result(item)
            return
        return_code = getattr(result, "return_code", None)
        if return_code in (None, 0):
            return
        stderr = getattr(result, "stderr", "") or ""
        stdout = getattr(result, "stdout", "") or ""
        detail = str(stderr).strip() or str(stdout).strip() or f"exit code {return_code}"
        raise RuntimeError(detail)
