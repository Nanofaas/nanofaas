"""
console.py — Centralized Rich UX layer for the controlplane tool.

All terminal output should go through the helpers here, never raw print().
"""
from __future__ import annotations

import sys
from contextvars import ContextVar
from contextlib import contextmanager
from typing import ContextManager, Generator, Protocol

from rich.console import Console
from rich.markup import escape
from rich.panel import Panel
from rich.rule import Rule
from rich.text import Text

from controlplane_tool.workflow_events import (
    build_log_event,
    build_phase_event,
    build_task_event,
)
from controlplane_tool.workflow_models import WorkflowContext, WorkflowEvent

# Singleton — shared across all modules.
console = Console(highlight=False)
_workflow_sink_var: ContextVar["WorkflowSink | None"] = ContextVar(
    "workflow_sink",
    default=None,
)
_workflow_sink_shared: "WorkflowSink | None" = None
_workflow_context_var: ContextVar[WorkflowContext | None] = ContextVar(
    "workflow_context",
    default=None,
)
_workflow_context_shared: WorkflowContext | None = None

_LOGO = r"""
 ███╗   ██╗ █████╗ ███╗   ██╗ ██████╗ ███████╗ █████╗  █████╗ ███████╗
 ████╗  ██║██╔══██╗████╗  ██║██╔═══██╗██╔════╝██╔══██╗██╔══██╗██╔════╝
 ██╔██╗ ██║███████║██╔██╗ ██║██║   ██║█████╗  ███████║███████║███████╗
 ██║╚██╗██║██╔══██║██║╚██╗██║██║   ██║██╔══╝  ██╔══██║██╔══██║╚════██║
 ██║ ╚████║██║  ██║██║ ╚████║╚██████╔╝██║     ██║  ██║██║  ██║███████║
 ╚═╝  ╚═══╝╚═╝  ╚═╝╚═╝  ╚═══╝ ╚═════╝ ╚═╝     ╚═╝  ╚═╝╚═╝  ╚═╝╚══════╝
"""


class WorkflowSink(Protocol):
    def emit(self, event: WorkflowEvent) -> None: ...

    def status(self, label: str) -> ContextManager[None]: ...


@contextmanager
def bind_workflow_sink(sink: WorkflowSink) -> Generator[None, None, None]:
    global _workflow_sink_shared
    previous_shared = _workflow_sink_shared
    _workflow_sink_shared = sink
    token = _workflow_sink_var.set(sink)
    try:
        yield
    finally:
        _workflow_sink_var.reset(token)
        _workflow_sink_shared = previous_shared


@contextmanager
def bind_workflow_context(context: WorkflowContext) -> Generator[None, None, None]:
    global _workflow_context_shared
    previous_shared = _workflow_context_shared
    _workflow_context_shared = context
    token = _workflow_context_var.set(context)
    try:
        yield
    finally:
        _workflow_context_var.reset(token)
        _workflow_context_shared = previous_shared


def _workflow_sink() -> WorkflowSink | None:
    return _workflow_sink_var.get() or _workflow_sink_shared


def _workflow_context() -> WorkflowContext | None:
    return _workflow_context_var.get() or _workflow_context_shared


def has_workflow_sink() -> bool:
    return _workflow_sink() is not None


def _render_event(event: WorkflowEvent) -> None:
    if event.kind == "log.line":
        prefix = "stderr │ " if event.stream == "stderr" else ""
        console.print(f"{prefix}{escape(event.line)}")
        return
    if event.kind == "phase.started":
        console.print()
        console.print(Rule(f"[bold cyan]{escape(event.title)}[/]", style="cyan dim"))
        console.print()
        return
    if event.kind == "task.running":
        if event.detail:
            console.print(
                f"  [cyan]▸[/] [bold]{escape(event.title)}[/]  [dim]{escape(event.detail)}[/]"
            )
        else:
            console.print(f"  [cyan]▸[/] [bold]{escape(event.title)}[/]")
        return
    if event.kind == "task.completed":
        body = f"[bold green]✓  {escape(event.title)}[/]"
        if event.detail:
            body += f"\n\n[dim]{escape(event.detail)}[/]"
        console.print()
        console.print(Panel(body, border_style="green", padding=(0, 2)))
        console.print()
        return
    if event.kind == "task.warning":
        console.print(f"  [yellow]⚠[/]  [yellow]{escape(event.title)}[/]")
        return
    if event.kind == "task.updated":
        if event.detail:
            console.print(
                f"  [cyan]↺[/] [bold]{escape(event.title)}[/]  [dim]{escape(event.detail)}[/]"
            )
        else:
            console.print(f"  [cyan]↺[/] [bold]{escape(event.title)}[/]")
        return
    if event.kind == "task.skipped":
        console.print(f"  [dim]⊘  {escape(event.title)}[/]")
        return
    if event.kind == "task.cancelled":
        body = f"[bold yellow]⊘  {escape(event.title)}[/]"
        if event.detail:
            body += f"\n\n[dim]{escape(event.detail)}[/]"
        console.print()
        console.print(Panel(body, border_style="yellow", padding=(0, 2)))
        console.print()
        return
    if event.kind == "task.failed":
        body = f"[bold red]✗  {escape(event.title)}[/]"
        if event.detail:
            body += f"\n\n[dim]{escape(event.detail)}[/]"
        console.print()
        console.print(Panel(body, border_style="red", padding=(0, 2)))
        console.print()


def _emit_workflow_event(event: WorkflowEvent) -> None:
    sink = _workflow_sink()
    if sink is not None:
        sink.emit(event)
        return
    _render_event(event)


def workflow_log(
    message: str,
    *,
    stream: str = "stdout",
    context: WorkflowContext | None = None,
) -> None:
    _emit_workflow_event(
        build_log_event(
            line=message,
            stream=stream,
            context=context or _workflow_context(),
        )
    )


def header(subtitle: str = "controlplane tool") -> None:
    """Startup banner — shown once when the TUI launches."""
    console.print()
    logo = Text(_LOGO, style="bold cyan", justify="center")
    console.print(logo)
    console.print(
        Panel(
            f"[dim]{escape(subtitle)}[/]",
            border_style="cyan dim",
            padding=(0, 4),
        )
    )
    console.print()


def phase(label: str) -> None:
    """Major workflow phase separator."""
    _emit_workflow_event(build_phase_event(label, context=_workflow_context()))


def step(label: str, detail: str = "") -> None:
    """A single step within a phase."""
    _emit_workflow_event(
        build_task_event(
            kind="task.running",
            title=label,
            detail=detail,
            context=_workflow_context(),
        )
    )


def success(label: str, detail: str = "") -> None:
    """Green success panel for workflow completion."""
    _emit_workflow_event(
        build_task_event(
            kind="task.completed",
            title=label,
            detail=detail,
            context=_workflow_context(),
        )
    )


def warning(label: str) -> None:
    """Non-fatal yellow warning."""
    _emit_workflow_event(
        build_task_event(
            kind="task.warning",
            title=label,
            context=_workflow_context(),
        )
    )


def skip(label: str) -> None:
    """Dimmed skip message."""
    _emit_workflow_event(
        build_task_event(
            kind="task.skipped",
            title=label,
            context=_workflow_context(),
        )
    )


def fail(label: str, detail: str = "") -> None:
    """Red failure panel."""
    _emit_workflow_event(
        build_task_event(
            kind="task.failed",
            title=label,
            detail=detail,
            context=_workflow_context(),
        )
    )


@contextmanager
def status(label: str) -> Generator[None, None, None]:
    """Spinner context manager for long-running operations."""
    sink = _workflow_sink()
    if sink is not None:
        with sink.status(label):
            yield
        return
    with console.status(f"[cyan]{escape(label)}…[/]", spinner="dots"):
        yield
