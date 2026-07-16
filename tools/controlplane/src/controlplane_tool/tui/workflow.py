from __future__ import annotations

from collections.abc import Callable, Generator
from contextlib import contextmanager
from dataclasses import dataclass, field
import os
import select
import sys
from threading import Event, Thread
import time
from typing import Any

from rich.console import Group, RenderableType
from rich.panel import Panel
from rich.table import Table
from rich.text import Text
from rich.tree import Tree

from controlplane_tool.tui.event_aggregator import WorkflowEventAggregator
from controlplane_tool.tui.models import TuiPhaseSnapshot, TuiWorkflowSnapshot
from tui_toolkit import render_screen_frame
from workflow_tasks.workflow.events import WorkflowEvent
from workflow_tasks.workflow.models import WorkflowState


@dataclass
class WorkflowStepState:
    label: str
    state: WorkflowState = "pending"
    detail: str = ""
    started_at: float | None = None
    finished_at: float | None = None
    children: list["WorkflowStepState"] = field(default_factory=list)


class WorkflowDashboard:
    def __init__(
        self,
        *,
        title: str,
        breadcrumb: str = "Main",
        footer_hint: str = "l toggle logs | Ctrl+C exit",
        summary_lines: list[str] | None = None,
        planned_steps: list[str] | None = None,
        log_limit: int = 200,
        aggregator: WorkflowEventAggregator | None = None,
    ) -> None:
        self.title = title
        self.breadcrumb = breadcrumb
        self.footer_hint = footer_hint
        self.summary_lines = list(summary_lines or [])
        self.error_detail: str | None = None
        self._aggregator = aggregator or WorkflowEventAggregator(
            planned_steps=planned_steps,
            log_limit=log_limit,
        )
        self.steps: list[WorkflowStepState] = []
        self.log_lines: list[str] = []
        self.show_logs = True
        self.sync_from_snapshot(self._aggregator.snapshot())

    def append_log(self, message: str) -> None:
        self._aggregator.append_log(message)
        self._sync()

    def toggle_logs(self) -> None:
        self._aggregator.toggle_logs()
        self._sync()

    def mark_step_running(self, step_index: int) -> None:
        self._aggregator.mark_phase_running(step_index)
        self._sync()

    def mark_step_success(self, step_index: int) -> None:
        self._aggregator.mark_phase_success(step_index)
        self._sync()

    def mark_step_failed(self, step_index: int, detail: str = "") -> None:
        self._aggregator.mark_phase_failed(step_index, detail=detail)
        self._sync()

    def mark_step_cancelled(self, step_index: int, detail: str = "") -> None:
        self._aggregator.mark_phase_cancelled(step_index, detail=detail)
        self._sync()

    def upsert_step(self, label: str, *, activate: bool = False, detail: str = "") -> int:
        index = self._aggregator.upsert_phase(label, detail=detail, activate=activate)
        self._sync()
        return index

    def complete_running_steps(
        self,
        *,
        state: WorkflowState = "success",
        detail: str = "",
    ) -> None:
        self._aggregator.complete_running_phases(status=state, detail=detail)
        self._sync()

    def apply_event(self, event: WorkflowEvent) -> None:
        self._aggregator.handle_event(event)
        self._sync()

    def _sync(self) -> None:
        self.sync_from_snapshot(self._aggregator.snapshot())

    def sync_from_snapshot(self, snapshot: TuiWorkflowSnapshot) -> None:
        def convert_phase(phase: TuiPhaseSnapshot) -> WorkflowStepState:
            return WorkflowStepState(
                label=phase.label,
                state=phase.status,
                detail=phase.detail,
                started_at=phase.started_at,
                finished_at=phase.finished_at,
                children=[convert_phase(child) for child in phase.children],
            )

        self.steps = [convert_phase(phase) for phase in snapshot.phases]
        self.log_lines = list(snapshot.logs)
        self.show_logs = snapshot.show_logs

    @staticmethod
    def _step_duration_seconds(step: WorkflowStepState) -> float | None:
        if step.started_at is None:
            return None
        end = step.finished_at if step.finished_at is not None else time.time()
        return max(0.0, end - step.started_at)

    def _format_step_duration(self, step: WorkflowStepState) -> str:
        duration = self._step_duration_seconds(step)
        return "" if duration is None else f"{duration:.1f}s"

    @staticmethod
    def _step_icon(state: WorkflowState) -> str:
        return {
            "running": "[cyan]●[/]",
            "success": "[green]✓[/]",
            "cancelled": "[yellow]⊘[/]",
            "failed": "[red]✗[/]",
        }.get(state, "[dim]○[/]")

    def _format_step_label(self, step: WorkflowStepState, *, index: int | None = None) -> str:
        prefix = f"{index}. " if index is not None else ""
        detail = f" [dim]{step.detail}[/]" if step.detail else ""
        return f"{self._step_icon(step.state)} {prefix}[bold]{step.label}[/]{detail}"

    def _nested_node_count(self, steps: list[WorkflowStepState]) -> int:
        return sum(1 + self._nested_node_count(step.children) for step in steps)

    def _nested_detail_panel(self) -> Panel | None:
        nested_roots = [step for step in self.steps if step.children]
        if not nested_roots:
            return None
        tree = Tree("[bold]Verification work[/]")

        def add_children(parent: Tree, step: WorkflowStepState) -> None:
            branch = parent.add(self._format_step_label(step))
            for child in step.children:
                add_children(branch, child)

        for step in nested_roots:
            add_children(tree, step)
        return Panel(tree, title="Nested Verification Work", border_style="cyan dim")

    def _summary_panel_height(self) -> int:
        return max(1, len(self.summary_lines) or 1) + 2

    def _phases_panel_height(self) -> int:
        return max(1, len(self.steps)) + 2

    def _nested_panel_height(self) -> int:
        nested_roots = [step for step in self.steps if step.children]
        return self._nested_node_count(nested_roots) + 2 if nested_roots else 0

    def _log_panel_height(self) -> int:
        return self._summary_panel_height() + self._phases_panel_height() + self._nested_panel_height()

    def _error_detail_panel(self) -> Panel | None:
        if not self.error_detail:
            return None
        return Panel(Text(self.error_detail.strip()), title="[red]Error Detail[/]", border_style="red")

    def render(self) -> RenderableType:
        summary_panel = Panel(
            Text("\n".join(self.summary_lines) or "No scenario details.", style="cyan"),
            title="Summary",
            border_style="cyan dim",
        )
        phases = Table.grid(padding=(0, 1))
        phases.expand = True
        phases.add_column(ratio=1)
        phases.add_column(justify="right", no_wrap=True)
        if self.steps:
            for index, step in enumerate(self.steps, start=1):
                duration = self._format_step_duration(step)
                phases.add_row(
                    self._format_step_label(step, index=index),
                    f"[dim]{duration}[/]" if duration else "",
                )
        else:
            phases.add_row("[dim]Waiting for workflow steps...[/]", "")
        phases_panel = Panel(phases, title="Execution Phases", border_style="cyan dim")
        nested_panel = self._nested_detail_panel()
        error_panel = self._error_detail_panel()
        left_pane: list[RenderableType] = [summary_panel, phases_panel]
        left_pane.extend(panel for panel in (nested_panel, error_panel) if panel is not None)

        if not self.show_logs:
            body: RenderableType = Group(*left_pane)
        else:
            max_log_lines = max(1, self._log_panel_height() - 2)
            log_body = (
                Text("\n".join(self.log_lines[-max_log_lines:]))
                if self.log_lines
                else Text("No log output yet.", style="dim")
            )
            log_panel = Panel(
                log_body,
                title="Raw Command Output",
                border_style="cyan dim",
                height=self._log_panel_height(),
            )
            layout = Table.grid(expand=True)
            layout.add_column(ratio=5)
            layout.add_column(ratio=7)
            layout.add_row(Group(*left_pane), log_panel)
            body = layout
        return render_screen_frame(
            title=self.title,
            body=body,
            breadcrumb=self.breadcrumb,
            footer_hint=self.footer_hint,
        )


class TuiWorkflowSink:
    def __init__(
        self,
        aggregator: WorkflowEventAggregator,
        *,
        refresh: Callable[[], None] | None = None,
    ) -> None:
        self._aggregator = aggregator
        self._refresh = refresh or (lambda: None)

    def emit(self, event: WorkflowEvent) -> None:
        self._aggregator.handle_event(event)
        self._refresh()

    @contextmanager
    def status(self, label: str) -> Generator[None, None, None]:
        self._aggregator.append_log(f"[wait] {label}")
        self._refresh()
        try:
            yield
        except Exception:
            self._aggregator.append_log(f"[wait-failed] {label}")
            self._refresh()
            raise
        else:
            self._aggregator.append_log(f"[wait-done] {label}")
            self._refresh()


class WorkflowKeyListener:
    def __init__(
        self,
        dashboard: WorkflowDashboard,
        refresh: Callable[[], None],
        *,
        input_stream: Any = None,
    ) -> None:
        self._dashboard = dashboard
        self._refresh = refresh
        self._input_stream = sys.stdin if input_stream is None else input_stream
        self._stop = Event()
        self._acknowledged = Event()
        self._waiting_for_acknowledgment = Event()
        self._thread: Thread | None = None
        self._termios: Any = None
        self._fd: int | None = None
        self._original_attrs: Any = None

    @property
    def input_is_tty(self) -> bool:
        return bool(
            hasattr(self._input_stream, "isatty")
            and self._input_stream.isatty()
        )

    def handle_key(self, key: str) -> None:
        if self._waiting_for_acknowledgment.is_set():
            self._acknowledged.set()
        elif key.lower() == "l":
            self._dashboard.toggle_logs()
            self._refresh()

    def wait_for_acknowledgment(self) -> None:
        self._waiting_for_acknowledgment.set()
        self._acknowledged.wait(timeout=60)

    def start(self) -> None:
        if not self.input_is_tty:
            return
        try:
            import termios
            import tty
        except ImportError:
            return
        self._fd = int(self._input_stream.fileno())
        self._termios = termios
        self._original_attrs = termios.tcgetattr(self._fd)
        tty.setcbreak(self._fd)

        def run() -> None:
            if self._fd is None:
                return
            while not self._stop.is_set():
                ready, _, _ = select.select([self._fd], [], [], 0.1)
                if ready:
                    char = os.read(self._fd, 1).decode(errors="ignore")
                    if char:
                        self.handle_key(char)

        self._thread = Thread(target=run, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=0.3)
        if self._termios is not None and self._fd is not None and self._original_attrs is not None:
            self._termios.tcsetattr(
                self._fd,
                self._termios.TCSADRAIN,
                self._original_attrs,
            )


__all__ = ["TuiWorkflowSink", "WorkflowDashboard", "WorkflowKeyListener", "WorkflowStepState"]
