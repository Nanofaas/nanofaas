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

WorkflowState = Literal["pending", "running", "success", "failed"]


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
        self.steps = [WorkflowStepState(label=label) for label in (planned_steps or [])]
        self.log_limit = log_limit
        self.log_lines: list[str] = []
        self.show_logs = True

    def append_log(self, message: str) -> None:
        self.log_lines.append(message)
        if len(self.log_lines) > self.log_limit:
            self.log_lines = self.log_lines[-self.log_limit :]

    def toggle_logs(self) -> None:
        self.show_logs = not self.show_logs

    def mark_step_running(self, step_index: int) -> None:
        step = self.steps[step_index - 1]
        for existing in self.steps:
            if existing is not step and existing.state == "running":
                existing.state = "success"
                existing.finished_at = existing.finished_at or time.time()
        step.state = "running"
        step.started_at = step.started_at or time.time()

    def mark_step_success(self, step_index: int) -> None:
        step = self.steps[step_index - 1]
        step.state = "success"
        step.finished_at = time.time()

    def mark_step_failed(self, step_index: int, detail: str = "") -> None:
        step = self.steps[step_index - 1]
        step.state = "failed"
        step.detail = detail or step.detail
        step.finished_at = time.time()

    def upsert_step(self, label: str, *, activate: bool = False, detail: str = "") -> int:
        for index, step in enumerate(self.steps, start=1):
            if step.label == label:
                if detail:
                    step.detail = detail
                if activate:
                    self.mark_step_running(index)
                return index
        self.steps.append(WorkflowStepState(label=label, detail=detail))
        index = len(self.steps)
        if activate:
            self.mark_step_running(index)
        return index

    def complete_running_steps(self, *, failed: bool = False, detail: str = "") -> None:
        for step in self.steps:
            if step.state != "running":
                continue
            step.state = "failed" if failed else "success"
            if detail:
                step.detail = detail
            step.finished_at = time.time()

    def render(self):
        summary = Text("\n".join(self.summary_lines) or "No scenario details.", style="cyan")
        summary_panel = Panel(summary, title=self.title, border_style="cyan dim")

        phases = Table.grid(padding=(0, 1))
        phases.expand = True
        if self.steps:
            for index, step in enumerate(self.steps, start=1):
                if step.state == "running":
                    icon = "[cyan]●[/]"
                elif step.state == "success":
                    icon = "[green]✓[/]"
                elif step.state == "failed":
                    icon = "[red]✗[/]"
                else:
                    icon = "[dim]○[/]"
                detail = f" [dim]{step.detail}[/]" if step.detail else ""
                phases.add_row(f"{icon} {index}. [bold]{step.label}[/]{detail}")
        else:
            phases.add_row("[dim]Waiting for workflow steps...[/]")
        phases_panel = Panel(phases, title="Execution Phases", border_style="cyan dim")

        log_body = (
            Text("\n".join(self.log_lines[-24:]))
            if self.log_lines
            else Text("No log output yet.", style="dim")
        )
        if not self.show_logs:
            return Group(summary_panel, phases_panel)

        log_panel = Panel(log_body, title="Execution Log", border_style="cyan dim")

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

    def log(self, message: str, stream: str = "stdout") -> None:
        prefix = "stderr │ " if stream == "stderr" else ""
        self.dashboard.append_log(f"{prefix}{message}")
        self._update()

    def phase(self, label: str) -> None:
        self.dashboard.upsert_step(label, activate=True)
        self.dashboard.append_log(f"[phase] {label}")
        self._update()

    def step(self, label: str, detail: str = "") -> None:
        current = next((step for step in self.dashboard.steps if step.state == "running"), None)
        if current is None:
            pending_index = next(
                (index for index, step in enumerate(self.dashboard.steps, start=1) if step.state == "pending"),
                None,
            )
            if pending_index is not None:
                self.dashboard.mark_step_running(pending_index)
                current = self.dashboard.steps[pending_index - 1]
            else:
                self.dashboard.upsert_step(label, activate=True)
                current = self.dashboard.steps[-1]
        if current is not None:
            current.detail = detail or label
        self.dashboard.append_log(f"[step] {label}" + (f" ({detail})" if detail else ""))
        self._update()

    def success(self, label: str, detail: str = "") -> None:
        self.dashboard.complete_running_steps()
        self.dashboard.append_log(f"[ok] {label}" + (f" ({detail})" if detail else ""))
        self._update()

    def warning(self, label: str) -> None:
        self.dashboard.append_log(f"[warn] {label}")
        self._update()

    def skip(self, label: str) -> None:
        self.dashboard.append_log(f"[skip] {label}")
        self._update()

    def fail(self, label: str, detail: str = "") -> None:
        self.dashboard.complete_running_steps(failed=True, detail=detail)
        self.dashboard.append_log(f"[fail] {label}" + (f" ({detail})" if detail else ""))
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
