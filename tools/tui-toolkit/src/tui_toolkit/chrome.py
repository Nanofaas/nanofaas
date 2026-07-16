"""render_screen_frame — Rich screen chrome (header / breadcrumb / footer)."""
from __future__ import annotations

from rich.console import Group, RenderableType
from rich.panel import Panel
from rich.rule import Rule
from rich.table import Table
from rich.text import Text

from tui_toolkit.context import get_ui


def render_screen_frame(
    *,
    title: str,
    body: RenderableType,
    breadcrumb: str | None = None,
    footer_hint: str | None = None,
) -> RenderableType:
    """Render fixed top branding above a themed screen panel.

    Reads brand and theme from the active UIContext.
    """
    ui = get_ui()
    theme = ui.theme
    brand = ui.brand
    resolved_breadcrumb = breadcrumb if breadcrumb is not None else brand.default_breadcrumb
    resolved_footer = footer_hint if footer_hint is not None else brand.default_footer_hint

    header = Table.grid(expand=True)
    header.add_column(ratio=1)
    header.add_column(justify="right", no_wrap=True)
    header.add_row(
        Text(brand.wordmark, style=theme.brand) if brand.wordmark else Text(""),
        Text(resolved_breadcrumb, style=theme.muted) if resolved_breadcrumb else Text(""),
    )

    content: list[RenderableType] = [header]
    content.append(Rule(style=theme.accent_dim))
    content.append(body)
    if resolved_footer:
        content.append(Rule(style=theme.accent_dim))
        content.append(Text(resolved_footer, style=theme.muted))

    panel = Panel(
        Group(*content),
        title=Text(title, style="bold"),
        border_style=theme.accent_dim,
        padding=(1, 2),
    )
    if not brand.ascii_logo:
        return panel
    return Group(Text(brand.ascii_logo, style=theme.brand), panel)
