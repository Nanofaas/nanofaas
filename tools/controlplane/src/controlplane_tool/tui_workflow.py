from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass, field
import os
import select
import sys
from threading import Event, Thread
import time
from typing import Callable, Literal

from rich.console import Group
from rich.panel import Panel
from rich.table import Table
from rich.text import Text
from rich.tree import Tree

from controlplane_tool.tui_prefect_bridge import TuiPrefectBridge
from controlplane_tool.workflow_models import WorkflowEvent

WorkflowState = Literal["pending", "running", "success", "failed", "cancelled"]


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
        summary_lines: list[str] | None = None,
        planned_steps: list[str] | None = None,
        log_limit: int = 200,
    ) -> None:
        self.title = title
        self.summary_lines = list(summary_lines or [])
        self.log_limit = log_limit
        self._bridge = TuiPrefectBridge(
            planned_steps=planned_steps,
            log_limit=log_limit,
        )
        self.steps: list[WorkflowStepState] = []
        self.log_lines: list[str] = []
        self.show_logs = True
        self.sync_from_snapshot(self._bridge.snapshot())

    def append_log(self, message: str) -> None:
        self._bridge.append_log(message)
        self.sync_from_snapshot(self._bridge.snapshot())

    def toggle_logs(self) -> None:
        self._bridge.toggle_logs()
        self.sync_from_snapshot(self._bridge.snapshot())

    def mark_step_running(self, step_index: int) -> None:
        for index in range(step_index - 1):
            if self._bridge.snapshot().phases[index].status == "running":
                self._bridge.mark_phase_success(index + 1)
        self._bridge.mark_phase_running(step_index)
        self.sync_from_snapshot(self._bridge.snapshot())

    def mark_step_success(self, step_index: int) -> None:
        self._bridge.mark_phase_success(step_index)
        self.sync_from_snapshot(self._bridge.snapshot())

    def mark_step_failed(self, step_index: int, detail: str = "") -> None:
        self._bridge.mark_phase_failed(step_index, detail=detail)
        self.sync_from_snapshot(self._bridge.snapshot())

    def mark_step_cancelled(self, step_index: int, detail: str = "") -> None:
        self._bridge.mark_phase_cancelled(step_index, detail=detail)
        self.sync_from_snapshot(self._bridge.snapshot())

    def upsert_step(self, label: str, *, activate: bool = False, detail: str = "") -> int:
        phase_index = self._bridge.upsert_phase(label, detail=detail, activate=activate)
        self.sync_from_snapshot(self._bridge.snapshot())
        return phase_index

    def complete_running_steps(
        self,
        *,
        state: WorkflowState = "success",
        detail: str = "",
    ) -> None:
        self._bridge.complete_running_phases(status=state, detail=detail)
        self.sync_from_snapshot(self._bridge.snapshot())

    def apply_event(self, event: WorkflowEvent) -> None:
        self._bridge.handle_event(event)
        self.sync_from_snapshot(self._bridge.snapshot())

    def sync_from_snapshot(self, snapshot) -> None:
        def _convert_phase(phase) -> WorkflowStepState:
            return WorkflowStepState(
                label=phase.label,
                state=phase.status,
                detail=phase.detail,
                started_at=phase.started_at,
                finished_at=phase.finished_at,
                children=[_convert_phase(child) for child in phase.children],
            )

        self.steps = [_convert_phase(phase) for phase in snapshot.phases]
        self.log_lines = list(snapshot.logs)
        self.show_logs = snapshot.show_logs

    @staticmethod
    def _step_duration_seconds(step: WorkflowStepState) -> float | None:
        if step.started_at is None:
            return None
        end = step.finished_at if step.finished_at is not None else time.time()
        duration = max(0.0, end - step.started_at)
        return duration

    def _format_step_duration(self, step: WorkflowStepState) -> str:
        duration = self._step_duration_seconds(step)
        if duration is None:
            return ""
        return f"{duration:.1f}s"

    @staticmethod
    def _step_icon(state: WorkflowState) -> str:
        if state == "running":
            return "[cyan]●[/]"
        if state == "success":
            return "[green]✓[/]"
        if state == "cancelled":
            return "[yellow]⊘[/]"
        if state == "failed":
            return "[red]✗[/]"
        return "[dim]○[/]"

    def _format_step_label(self, step: WorkflowStepState, *, index: int | None = None) -> str:
        prefix = f"{index}. " if index is not None else ""
        detail = f" [dim]{step.detail}[/]" if step.detail else ""
        return f"{self._step_icon(step.state)} {prefix}[bold]{step.label}[/]{detail}"

    def _nested_node_count(self, steps: list[WorkflowStepState]) -> int:
        return sum(1 + self._nested_node_count(step.children) for step in steps)

    def _nested_detail_panel(self):
        nested_roots = [step for step in self.steps if step.children]
        if not nested_roots:
            return None

        tree = Tree("[bold]Verification work[/]")

        def _add_children(parent_node: Tree, step: WorkflowStepState) -> None:
            branch = parent_node.add(self._format_step_label(step))
            for child in step.children:
                _add_children(branch, child)

        for step in nested_roots:
            _add_children(tree, step)

        return Panel(tree, title="Nested Verification Work", border_style="cyan dim")

    def _summary_panel_height(self) -> int:
        return max(1, len(self.summary_lines) or 1) + 2

    def _phases_panel_height(self) -> int:
        return max(1, len(self.steps)) + 2

    def _nested_panel_height(self) -> int:
        nested_roots = [step for step in self.steps if step.children]
        if not nested_roots:
            return 0
        return self._nested_node_count(nested_roots) + 2

    def _log_panel_height(self) -> int:
        return self._summary_panel_height() + self._phases_panel_height() + self._nested_panel_height()

    def render(self):
        summary = Text("\n".join(self.summary_lines) or "No scenario details.", style="cyan")
        summary_panel = Panel(summary, title=self.title, border_style="cyan dim")

        phases = Table.grid(padding=(0, 1))
        phases.expand = True
        phases.add_column(ratio=1)
        phases.add_column(justify="right", no_wrap=True)
        if self.steps:
            for index, step in enumerate(self.steps, start=1):
                duration = self._format_step_duration(step)
                phases.add_row(self._format_step_label(step, index=index), f"[dim]{duration}[/]" if duration else "")
        else:
            phases.add_row("[dim]Waiting for workflow steps...[/]", "")
        phases_panel = Panel(phases, title="Execution Phases", border_style="cyan dim")
        nested_panel = self._nested_detail_panel()

        max_log_lines = max(1, self._log_panel_height() - 2)
        log_body = (
            Text("\n".join(self.log_lines[-max_log_lines:]))
            if self.log_lines
            else Text("No log output yet.", style="dim")
        )
        if not self.show_logs:
            return Group(summary_panel, phases_panel, nested_panel) if nested_panel is not None else Group(summary_panel, phases_panel)

        log_panel = Panel(
            log_body,
            title="Raw Command Output",
            border_style="cyan dim",
            height=self._log_panel_height(),
        )

        layout = Table.grid(expand=True)
        layout.add_column(ratio=5)
        layout.add_column(ratio=7)
        left_pane = [summary_panel, phases_panel]
        if nested_panel is not None:
            left_pane.append(nested_panel)
        layout.add_row(Group(*left_pane), log_panel)
        return layout


class TuiWorkflowSink:
    def __init__(
        self,
        dashboard: WorkflowDashboard,
        *,
        refresh: Callable[[], None] | None = None,
    ) -> None:
        self.dashboard = dashboard
        self._refresh = refresh or (lambda: None)

    def _update(self) -> None:
        self._refresh()

    def emit(self, event: WorkflowEvent) -> None:
        self.dashboard.apply_event(event)
        self._update()

    @contextmanager
    def status(self, label: str):
        self.dashboard.append_log(f"[wait] {label}")
        self._update()
        try:
            yield
        except Exception:
            self.dashboard.append_log(f"[wait-failed] {label}")
            self._update()
            raise
        else:
            self.dashboard.append_log(f"[wait-done] {label}")
            self._update()


class WorkflowKeyListener:
    def __init__(
        self,
        on_key: Callable[[str], None],
        *,
        input_stream=None,
    ) -> None:
        self._on_key = on_key
        self._input_stream = input_stream or sys.stdin
        self._stop = Event()
        self._thread: Thread | None = None
        self._termios = None
        self._fd: int | None = None

    def start(self) -> None:
        if not hasattr(self._input_stream, "isatty") or not self._input_stream.isatty():
            return
        try:
            import termios
            import tty
        except ImportError:
            return
        self._fd = self._input_stream.fileno()
        self._termios = termios
        original = termios.tcgetattr(self._fd)
        tty.setcbreak(self._fd)

        def _run() -> None:
            while not self._stop.is_set():
                ready, _, _ = select.select([self._fd], [], [], 0.1)
                if not ready:
                    continue
                char = os.read(self._fd, 1).decode(errors="ignore")
                if char:
                    self._on_key(char)

        self._thread = Thread(target=_run, daemon=True)
        self._thread.start()
        self._original_attrs = original

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=0.3)
        if self._termios is not None and self._fd is not None:
            self._termios.tcsetattr(self._fd, self._termios.TCSADRAIN, self._original_attrs)
