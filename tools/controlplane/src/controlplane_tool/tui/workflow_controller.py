from __future__ import annotations

from collections.abc import Callable
import traceback
from typing import Any

from rich.console import Console
from rich.live import Live

from controlplane_tool.tui.event_aggregator import WorkflowEventAggregator
from controlplane_tool.tui.models import TuiPhaseSnapshot
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
            footer_hint="l toggle logs | Ctrl+C exit",
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
        error: BaseException | None = None

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
                        phases: list[TuiPhaseSnapshot] = []

                        def collect(items: list[TuiPhaseSnapshot]) -> None:
                            for item in items:
                                phases.append(item)
                                collect(item.children)

                        collect(snapshot.phases)
                        targets = [phase for phase in phases if phase.status == "running"]
                        if not targets:
                            pending = next(
                                (phase for phase in phases if phase.status == "pending"),
                                None,
                            )
                            targets = [pending] if pending is not None else []
                        if not targets:
                            targets = [TuiPhaseSnapshot(label="Workflow failed", task_id="workflow.failure")]
                        for phase in targets:
                            sink.emit(
                                build_task_event(
                                    kind="task.failed",
                                    task_id=phase.task_id,
                                    parent_task_id=phase.parent_task_id,
                                    title=phase.label,
                                    detail=str(exc),
                                )
                            )
                    dashboard.footer_hint = "Press any key to continue"
                    refresh()
                    if listener.input_is_tty:
                        listener.wait_for_acknowledgment()
            except BaseException as exc:
                if error is None:
                    error = exc
                else:
                    error.add_note(f"Additional live workflow error: {exc}")
            finally:
                try:
                    listener.stop()
                except BaseException as cleanup_error:
                    if error is None:
                        error = cleanup_error
                    else:
                        error.add_note(f"Workflow listener cleanup failed: {cleanup_error}")

        if error is not None:
            raise error
        return result


__all__ = ["TuiWorkflowController"]
