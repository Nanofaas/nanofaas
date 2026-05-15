"""tui-toolkit — terminal UI widgets with unified theming.

Workflow event types and reporting helpers live in workflow_tasks.
"""
from __future__ import annotations

__version__ = "0.1.0"

# theming + setup
from tui_toolkit.brand import AppBrand, DEFAULT_BRAND
from tui_toolkit.context import UIContext, bind_ui, get_ui, init_ui
from tui_toolkit.theme import DEFAULT_THEME, Theme

# rendering primitives
from tui_toolkit.chrome import render_screen_frame
import tui_toolkit.console as console  # noqa: F401
from tui_toolkit.console import get_content_width

# pickers
from tui_toolkit.pickers import Choice, Separator, multiselect, select

# startup banner
from tui_toolkit.workflow import header

__all__ = [
    "__version__",
    # theming + setup
    "AppBrand", "DEFAULT_BRAND",
    "UIContext", "bind_ui", "get_ui", "init_ui",
    "DEFAULT_THEME", "Theme",
    # rendering primitives
    "render_screen_frame",
    "console", "get_content_width",
    # pickers
    "Choice", "Separator", "multiselect", "select",
    # startup banner
    "header",
]
