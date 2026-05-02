"""tui-toolkit — terminal UI widgets and workflow event renderer.

Single-source-of-truth theming via Theme + AppBrand + UIContext. Configure
once at startup with init_ui(); every widget reads the active context.
"""
from __future__ import annotations

__version__ = "0.1.0"

# theming + setup
from tui_toolkit.brand import AppBrand, DEFAULT_BRAND
from tui_toolkit.context import UIContext, bind_ui, get_ui, init_ui
from tui_toolkit.theme import DEFAULT_THEME, Theme

# rendering primitives
from tui_toolkit.chrome import render_screen_frame
# Import the module itself as 'console' so that:
#   tt.console          → the module (is not None ✓)
#   import tui_toolkit.console as m → still resolves to the module ✓
# Callers that need the Rich Console singleton use tui_toolkit.console.console.
import tui_toolkit.console as console  # noqa: F401
from tui_toolkit.console import get_content_width

# pickers
from tui_toolkit.pickers import Choice, Separator, multiselect, select

# workflow events
from tui_toolkit.events import WorkflowContext, WorkflowEvent, WorkflowSink
from tui_toolkit.workflow import (
    bind_workflow_context,
    bind_workflow_sink,
    build_log_event,
    build_phase_event,
    build_task_event,
    fail,
    get_workflow_context,
    has_workflow_sink,
    header,
    phase,
    skip,
    status,
    step,
    success,
    warning,
    workflow_log,
    workflow_step,
)

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
    # workflow events
    "WorkflowContext", "WorkflowEvent", "WorkflowSink",
    "bind_workflow_context", "bind_workflow_sink",
    "build_log_event", "build_phase_event", "build_task_event",
    "fail", "get_workflow_context", "has_workflow_sink",
    "header", "phase", "skip", "status", "step", "success", "warning",
    "workflow_log", "workflow_step",
]
