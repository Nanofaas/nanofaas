from __future__ import annotations

from rich.console import Group, RenderableType
from rich.panel import Panel
from rich.rule import Rule
from rich.table import Table
from rich.text import Text

APP_WORDMARK = "NANOFAAS"
APP_ASCII_LOGO = """
 ███╗   ██╗ █████╗ ███╗   ██╗ ██████╗ ███████╗ █████╗  █████╗ ███████╗
 ████╗  ██║██╔══██╗████╗  ██║██╔═══██╗██╔════╝██╔══██╗██╔══██╗██╔════╝
 ██╔██╗ ██║███████║██╔██╗ ██║██║   ██║█████╗  ███████║███████║███████╗
 ██║╚██╗██║██╔══██║██║╚██╗██║██║   ██║██╔══╝  ██╔══██║██╔══██║╚════██║
 ██║ ╚████║██║  ██║██║ ╚████║╚██████╔╝██║     ██║  ██║██║  ██║███████║
 ╚═╝  ╚═══╝╚═╝  ╚═╝╚═╝  ╚═══╝ ╚═════╝ ╚═╝     ╚═╝  ╚═╝╚═╝  ╚═╝╚══════╝
""".strip("\n")
APP_BRAND = APP_WORDMARK
DEFAULT_BREADCRUMB = "Main"
DEFAULT_FOOTER_HINT = "Esc back | Ctrl+C exit"


def render_screen_frame(
    *,
    title: str,
    body: RenderableType,
    breadcrumb: str = DEFAULT_BREADCRUMB,
    footer_hint: str = DEFAULT_FOOTER_HINT,
) -> Panel:
    header = Table.grid(expand=True)
    header.add_column(ratio=1)
    header.add_column(justify="right", no_wrap=True)
    header.add_row(
        Text(APP_WORDMARK, style="bold cyan"),
        Text(breadcrumb, style="dim") if breadcrumb else Text(""),
    )

    content: list[RenderableType] = [
        Text(APP_ASCII_LOGO, style="bold cyan"),
        header,
        Rule(style="cyan dim"),
        body,
    ]
    if footer_hint:
        content.extend([Rule(style="cyan dim"), Text(footer_hint, style="dim")])

    return Panel(
        Group(*content),
        title=Text(title, style="bold"),
        border_style="cyan dim",
        padding=(1, 2),
    )
