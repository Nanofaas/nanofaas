"""SHIM — moved to tui_toolkit.

This file will be deleted in PR2. New code should import directly:

    from tui_toolkit import console, phase, step, success, warning, skip, fail
    from tui_toolkit import status, workflow_log, workflow_step, header
    from tui_toolkit import bind_workflow_sink, bind_workflow_context
    from tui_toolkit import get_workflow_context, has_workflow_sink
    from tui_toolkit import get_content_width
"""
from __future__ import annotations

import tui_toolkit.console as _console_module
from tui_toolkit import (
    bind_workflow_context,
    bind_workflow_sink,
    fail,
    get_content_width,
    get_workflow_context,
    has_workflow_sink,
    header,
    init_ui,
    phase,
    skip,
    status,
    step,
    success,
    warning,
    workflow_log,
    workflow_step,
)
from tui_toolkit.workflow import _active_sink as _workflow_sink_getter
from tui_toolkit.workflow import _emit as _emit_workflow_event
from tui_toolkit.workflow import _render_event

# The Rich console singleton — accessed via the module to avoid shadowing.
console = _console_module.console


def _workflow_context():
    """Legacy alias — returns the active workflow context (or None)."""
    return get_workflow_context()


def _workflow_sink():
    """Legacy alias — returns the active workflow sink (or None)."""
    return _workflow_sink_getter()


def init_ui_width() -> None:
    """Legacy alias — initialises the nanofaas UI (brand + theme + terminal width)."""
    from controlplane_tool.ui_setup import setup_ui
    setup_ui()


__all__ = [
    "console",
    "get_content_width",
    "init_ui_width",
    "header", "phase", "step", "success", "warning", "skip", "fail",
    "status", "workflow_log", "workflow_step",
    "bind_workflow_sink", "bind_workflow_context",
    "has_workflow_sink",
    "_workflow_sink", "_workflow_context",
    "_render_event", "_emit_workflow_event",
]
