from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
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
        self.steps = [
            WorkflowStepState(
                label=phase.label,
                state=phase.status,
                detail=phase.detail,
                started_at=phase.started_at,
                finished_at=phase.finished_at,
            )
            for phase in snapshot.phases
        ]
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

    def _summary_panel_height(self) -> int:
        return max(1, len(self.summary_lines) or 1) + 2

    def _phases_panel_height(self) -> int:
        return max(1, len(self.steps)) + 2

    def _log_panel_height(self) -> int:
        return self._summary_panel_height() + self._phases_panel_height()

    def render(self):
        summary = Text("\n".join(self.summary_lines) or "No scenario details.", style="cyan")
        summary_panel = Panel(summary, title=self.title, border_style="cyan dim")

        phases = Table.grid(padding=(0, 1))
        phases.expand = True
        phases.add_column(ratio=1)
        phases.add_column(justify="right", no_wrap=True)
        if self.steps:
            for index, step in enumerate(self.steps, start=1):
                if step.state == "running":
                    icon = "[cyan]●[/]"
                elif step.state == "success":
                    icon = "[green]✓[/]"
                elif step.state == "cancelled":
                    icon = "[yellow]⊘[/]"
                elif step.state == "failed":
                    icon = "[red]✗[/]"
                else:
                    icon = "[dim]○[/]"
                detail = f" [dim]{step.detail}[/]" if step.detail else ""
                duration = self._format_step_duration(step)
                phases.add_row(
                    f"{icon} {index}. [bold]{step.label}[/]{detail}",
                    f"[dim]{duration}[/]" if duration else "",
                )
        else:
            phases.add_row("[dim]Waiting for workflow steps...[/]", "")
        phases_panel = Panel(phases, title="Execution Phases", border_style="cyan dim")

        max_log_lines = max(1, self._log_panel_height() - 2)
        log_body = (
            Text("\n".join(self.log_lines[-max_log_lines:]))
            if self.log_lines
            else Text("No log output yet.", style="dim")
        )
        if not self.show_logs:
            return Group(summary_panel, phases_panel)

        log_panel = Panel(
            log_body,
            title="Execution Log",
            border_style="cyan dim",
            height=self._log_panel_height(),
        )

        layout = Table.grid(expand=True)
        layout.add_column(ratio=5)
        layout.add_column(ratio=7)
        layout.add_row(Group(summary_panel, phases_panel), log_panel)
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
