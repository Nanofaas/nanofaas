"""tui-toolkit workflow — startup banner only.

The workflow event types, context management, event builders, and reporting
helpers live in workflow_tasks. The Rich renderer lives in
controlplane_tool/tui/workflow_renderer.py.
"""
from __future__ import annotations

from rich.markup import escape
from rich.panel import Panel
from rich.text import Text

import tui_toolkit.console as _console_mod
from tui_toolkit.context import get_ui


def header(subtitle: str | None = None) -> None:
    """Startup banner — uses the brand from the active UIContext."""
    ui = get_ui()
    _con = _console_mod.console
    if ui.brand.ascii_logo:
        _con.print()
        _con.print(Text(ui.brand.ascii_logo, style=ui.theme.brand, justify="center"))
    if subtitle:
        _con.print(
            Panel(
                f"[{ui.theme.muted}]{escape(subtitle)}[/]",
                border_style=ui.theme.accent_dim,
                padding=(0, 4),
            )
        )
    _con.print()
