"""Theme + brand configuration for the nanofaas control-plane tool.

This is the single source of truth for the visual identity. Every widget
in tui-toolkit reads from the active UIContext set up by setup_ui().
"""
from __future__ import annotations

from tui_toolkit import AppBrand, Theme, UIContext, init_ui

NANOFAAS_THEME = Theme()  # the cyan default already matches the historical palette

NANOFAAS_BRAND = AppBrand(
    name="nanofaas",
    wordmark="NANOFAAS",
    ascii_logo="""
 ███╗   ██╗ █████╗ ███╗   ██╗ ██████╗ ███████╗ █████╗  █████╗ ███████╗
 ████╗  ██║██╔══██╗████╗  ██║██╔═══██╗██╔════╝██╔══██╗██╔══██╗██╔════╝
 ██╔██╗ ██║███████║██╔██╗ ██║██║   ██║█████╗  ███████║███████║███████╗
 ██║╚██╗██║██╔══██║██║╚██╗██║██║   ██║██╔══╝  ██╔══██║██╔══██║╚════██║
 ██║ ╚████║██║  ██║██║ ╚████║╚██████╔╝██║     ██║  ██║██║  ██║███████║
 ╚═╝  ╚═══╝╚═╝  ╚═╝╚═╝  ╚═══╝ ╚═════╝ ╚═╝     ╚═╝  ╚═╝╚═╝  ╚═╝╚══════╝
""".strip("\n"),
    default_breadcrumb="Main",
    default_footer_hint="Esc back | Ctrl+C exit",
)


def setup_ui() -> UIContext:
    """Install the nanofaas theme and brand. Idempotent. Call once at startup."""
    return init_ui(UIContext(theme=NANOFAAS_THEME, brand=NANOFAAS_BRAND))
