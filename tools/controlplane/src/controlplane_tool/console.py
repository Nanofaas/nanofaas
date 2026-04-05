"""
console.py — Centralized Rich UX layer for the controlplane tool.

All terminal output should go through the helpers here, never raw print().
"""
from __future__ import annotations

import sys
from contextlib import contextmanager
from typing import Generator

from rich.console import Console
from rich.markup import escape
from rich.panel import Panel
from rich.rule import Rule
from rich.text import Text

# Singleton — shared across all modules.
console = Console(highlight=False)

_LOGO = r"""
 ███╗   ██╗ █████╗ ███╗   ██╗ ██████╗ ███████╗ █████╗  █████╗ ███████╗
 ████╗  ██║██╔══██╗████╗  ██║██╔═══██╗██╔════╝██╔══██╗██╔══██╗██╔════╝
 ██╔██╗ ██║███████║██╔██╗ ██║██║   ██║█████╗  ███████║███████║███████╗
 ██║╚██╗██║██╔══██║██║╚██╗██║██║   ██║██╔══╝  ██╔══██║██╔══██║╚════██║
 ██║ ╚████║██║  ██║██║ ╚████║╚██████╔╝██║     ██║  ██║██║  ██║███████║
 ╚═╝  ╚═══╝╚═╝  ╚═╝╚═╝  ╚═══╝ ╚═════╝ ╚═╝     ╚═╝  ╚═╝╚═╝  ╚═╝╚══════╝
"""


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
    console.print()
    console.print(Rule(f"[bold cyan]{escape(label)}[/]", style="cyan dim"))
    console.print()


def step(label: str, detail: str = "") -> None:
    """A single step within a phase."""
    if detail:
        console.print(f"  [cyan]▸[/] [bold]{escape(label)}[/]  [dim]{escape(detail)}[/]")
    else:
        console.print(f"  [cyan]▸[/] [bold]{escape(label)}[/]")


def success(label: str, detail: str = "") -> None:
    """Green success panel for workflow completion."""
    body = f"[bold green]✓  {escape(label)}[/]"
    if detail:
        body += f"\n\n[dim]{escape(detail)}[/]"
    console.print()
    console.print(Panel(body, border_style="green", padding=(0, 2)))
    console.print()


def warning(label: str) -> None:
    """Non-fatal yellow warning."""
    console.print(f"  [yellow]⚠[/]  [yellow]{escape(label)}[/]")


def skip(label: str) -> None:
    """Dimmed skip message."""
    console.print(f"  [dim]⊘  {escape(label)}[/]")


def fail(label: str, detail: str = "") -> None:
    """Red failure panel."""
    body = f"[bold red]✗  {escape(label)}[/]"
    if detail:
        body += f"\n\n[dim]{escape(detail)}[/]"
    console.print()
    console.print(Panel(body, border_style="red", padding=(0, 2)))
    console.print()


@contextmanager
def status(label: str) -> Generator[None, None, None]:
    """Spinner context manager for long-running operations."""
    with console.status(f"[cyan]{escape(label)}…[/]", spinner="dots"):
        yield
