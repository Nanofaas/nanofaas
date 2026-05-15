from __future__ import annotations

from contextlib import contextmanager
from typing import Generator

from rich.markup import escape
from rich.panel import Panel
from rich.rule import Rule
from rich.text import Text

import tui_toolkit.console as _console_mod
from tui_toolkit.context import get_ui
from workflow_tasks.workflow.events import WorkflowEvent


def render_event(event: WorkflowEvent) -> None:
    """Render a WorkflowEvent to the active Rich console using the active theme."""
    theme = get_ui().theme
    _con = _console_mod.console

    if event.kind == "log.line":
        prefix = "stderr │ " if event.stream == "stderr" else ""
        _con.print(f"{prefix}{escape(event.line)}")
        return
    if event.kind == "phase.started":
        _con.print()
        _con.print(Rule(f"[{theme.accent_strong}]{escape(event.title)}[/]", style=theme.accent_dim))
        _con.print()
        return
    if event.kind == "task.running":
        if event.detail:
            _con.print(
                f"  [{theme.accent}]{theme.icon_running}[/] [bold]{escape(event.title)}[/]  "
                f"[{theme.muted}]{escape(event.detail)}[/]"
            )
        else:
            _con.print(f"  [{theme.accent}]{theme.icon_running}[/] [bold]{escape(event.title)}[/]")
        return
    if event.kind == "task.completed":
        body = f"[bold {theme.success}]{theme.icon_completed}  {escape(event.title)}[/]"
        if event.detail:
            body += f"\n\n[{theme.muted}]{escape(event.detail)}[/]"
        _con.print()
        _con.print(Panel(body, border_style=theme.success, padding=(0, 2)))
        _con.print()
        return
    if event.kind == "task.warning":
        _con.print(f"  [{theme.warning}]{theme.icon_warning}[/]  [{theme.warning}]{escape(event.title)}[/]")
        return
    if event.kind == "task.updated":
        if event.detail:
            _con.print(
                f"  [{theme.accent}]{theme.icon_updated}[/] [bold]{escape(event.title)}[/]  "
                f"[{theme.muted}]{escape(event.detail)}[/]"
            )
        else:
            _con.print(f"  [{theme.accent}]{theme.icon_updated}[/] [bold]{escape(event.title)}[/]")
        return
    if event.kind == "task.skipped":
        _con.print(f"  [{theme.muted}]{theme.icon_skipped}  {escape(event.title)}[/]")
        return
    if event.kind == "task.cancelled":
        body = f"[bold {theme.warning}]{theme.icon_cancelled}  {escape(event.title)}[/]"
        if event.detail:
            body += f"\n\n[{theme.muted}]{escape(event.detail)}[/]"
        _con.print()
        _con.print(Panel(body, border_style=theme.warning, padding=(0, 2)))
        _con.print()
        return
    if event.kind == "task.failed":
        body = f"[bold {theme.error}]{theme.icon_failed}  {escape(event.title)}[/]"
        if event.detail:
            body += f"\n\n[{theme.muted}]{escape(event.detail)}[/]"
        _con.print()
        _con.print(Panel(body, border_style=theme.error, padding=(0, 2)))
        _con.print()


class RichWorkflowSink:
    """WorkflowSink that renders events to the active Rich console."""

    def emit(self, event: WorkflowEvent) -> None:
        render_event(event)

    @contextmanager
    def status(self, label: str) -> Generator[None, None, None]:
        with _console_mod.console.status(
            f"[{get_ui().theme.accent}]{escape(label)}…[/]", spinner="dots"
        ):
            yield
