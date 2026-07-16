from __future__ import annotations

from collections.abc import Callable
import traceback
from typing import Any

from rich.console import Console
from rich.live import Live

from controlplane_tool.tui.event_aggregator import WorkflowEventAggregator
from controlplane_tool.tui.workflow import TuiWorkflowSink, WorkflowDashboard, WorkflowKeyListener
from tui_toolkit.console import console as default_console
from workflow_tasks import bind_workflow_sink
from workflow_tasks.workflow.event_builders import build_task_event


class TuiWorkflowController:
    def __init__(self, *, console: Console = default_console) -> None:
        self.console = console

    def run_live_workflow(
        self,
        *,
        title: str,
        summary_lines: list[str],
        planned_steps: list[str] | None,
        action: Callable[[WorkflowDashboard, TuiWorkflowSink], Any],
    ) -> Any:
        aggregator = WorkflowEventAggregator(planned_steps=planned_steps)
        dashboard = WorkflowDashboard(
            title=title,
            breadcrumb=f"Main / {title}",
            footer_hint="l toggle logs | Ctrl+C back",
            summary_lines=summary_lines,
            aggregator=aggregator,
        )
        live: Live | None = None

        def refresh() -> None:
            dashboard.sync_from_snapshot(aggregator.snapshot())
            if live is not None:
                live.update(dashboard.render(), refresh=True)

        sink = TuiWorkflowSink(aggregator, refresh=refresh)
        listener = WorkflowKeyListener(dashboard, refresh)
        result: Any = None
        error: Exception | None = None

        self.console.clear()
        with Live(
            dashboard.render(),
            console=self.console,
            refresh_per_second=8,
            transient=False,
        ) as active_live:
            live = active_live
            refresh()
            try:
                listener.start()
                with bind_workflow_sink(sink):
                    try:
                        result = action(dashboard, sink)
                    except Exception as exc:
                        error = exc
                        dashboard.error_detail = traceback.format_exc(limit=12)
                        snapshot = aggregator.snapshot()
                        phase = next(
                            (candidate for candidate in snapshot.phases if candidate.status == "running"),
                            snapshot.phases[0] if snapshot.phases else None,
                        )
                        sink.emit(
                            build_task_event(
                                kind="task.failed",
                                task_id=phase.task_id if phase else "workflow.failure",
                                parent_task_id=phase.parent_task_id if phase else None,
                                title=phase.label if phase else "Workflow failed",
                                detail=str(exc),
                            )
                        )
                    dashboard.footer_hint = "Press any key to continue"
                    refresh()
                    if listener.input_is_tty:
                        listener.wait_for_acknowledgment()
            finally:
                listener.stop()

        if error is not None:
            raise error
        return result


__all__ = ["TuiWorkflowController"]
