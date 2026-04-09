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

# Singleton — shared across all modules.
console = Console(highlight=False)
_workflow_sink_var: ContextVar["WorkflowSink | None"] = ContextVar(
    "workflow_sink",
    default=None,
)
_workflow_sink_shared: "WorkflowSink | None" = None

_LOGO = r"""
 ███╗   ██╗ █████╗ ███╗   ██╗ ██████╗ ███████╗ █████╗  █████╗ ███████╗
 ████╗  ██║██╔══██╗████╗  ██║██╔═══██╗██╔════╝██╔══██╗██╔══██╗██╔════╝
 ██╔██╗ ██║███████║██╔██╗ ██║██║   ██║█████╗  ███████║███████║███████╗
 ██║╚██╗██║██╔══██║██║╚██╗██║██║   ██║██╔══╝  ██╔══██║██╔══██║╚════██║
 ██║ ╚████║██║  ██║██║ ╚████║╚██████╔╝██║     ██║  ██║██║  ██║███████║
 ╚═╝  ╚═══╝╚═╝  ╚═╝╚═╝  ╚═══╝ ╚═════╝ ╚═╝     ╚═╝  ╚═╝╚═╝  ╚═╝╚══════╝
"""


class WorkflowSink(Protocol):
    def phase(self, label: str) -> None: ...

    def step(self, label: str, detail: str = "") -> None: ...

    def success(self, label: str, detail: str = "") -> None: ...

    def warning(self, label: str) -> None: ...

    def skip(self, label: str) -> None: ...

    def fail(self, label: str, detail: str = "") -> None: ...

    def status(self, label: str) -> ContextManager[None]: ...

    def log(self, message: str, stream: str = "stdout") -> None: ...


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


def _workflow_sink() -> WorkflowSink | None:
    return _workflow_sink_var.get() or _workflow_sink_shared


def has_workflow_sink() -> bool:
    return _workflow_sink() is not None


def workflow_log(message: str, *, stream: str = "stdout") -> None:
    sink = _workflow_sink()
    if sink is None:
        return
    sink.log(message, stream=stream)


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
    sink = _workflow_sink()
    if sink is not None:
        sink.phase(label)
        return
    console.print()
    console.print(Rule(f"[bold cyan]{escape(label)}[/]", style="cyan dim"))
    console.print()


def step(label: str, detail: str = "") -> None:
    """A single step within a phase."""
    sink = _workflow_sink()
    if sink is not None:
        sink.step(label, detail)
        return
    if detail:
        console.print(f"  [cyan]▸[/] [bold]{escape(label)}[/]  [dim]{escape(detail)}[/]")
    else:
        console.print(f"  [cyan]▸[/] [bold]{escape(label)}[/]")


def success(label: str, detail: str = "") -> None:
    """Green success panel for workflow completion."""
    sink = _workflow_sink()
    if sink is not None:
        sink.success(label, detail)
        return
    body = f"[bold green]✓  {escape(label)}[/]"
    if detail:
        body += f"\n\n[dim]{escape(detail)}[/]"
    console.print()
    console.print(Panel(body, border_style="green", padding=(0, 2)))
    console.print()


def warning(label: str) -> None:
    """Non-fatal yellow warning."""
    sink = _workflow_sink()
    if sink is not None:
        sink.warning(label)
        return
    console.print(f"  [yellow]⚠[/]  [yellow]{escape(label)}[/]")


def skip(label: str) -> None:
    """Dimmed skip message."""
    sink = _workflow_sink()
    if sink is not None:
        sink.skip(label)
        return
    console.print(f"  [dim]⊘  {escape(label)}[/]")


def fail(label: str, detail: str = "") -> None:
    """Red failure panel."""
    sink = _workflow_sink()
    if sink is not None:
        sink.fail(label, detail)
        return
    body = f"[bold red]✗  {escape(label)}[/]"
    if detail:
        body += f"\n\n[dim]{escape(detail)}[/]"
    console.print()
    console.print(Panel(body, border_style="red", padding=(0, 2)))
    console.print()


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
