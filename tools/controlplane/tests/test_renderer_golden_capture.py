"""Capture golden snapshots of the legacy renderer for tui-toolkit parity tests.

Run once with:
    cd tools/controlplane && uv run pytest tests/test_renderer_golden_capture.py -v -s

After running, verify the files appear under tools/tui-toolkit/tests/golden/.
This file is deleted at the end of PR2.
"""
from __future__ import annotations

from pathlib import Path

from rich.console import Console
from controlplane_tool.console import _render_event
from controlplane_tool.workflow_events import (
    build_log_event,
    build_phase_event,
    build_task_event,
)
from controlplane_tool.workflow_models import WorkflowContext
from controlplane_tool.tui_widgets import _STYLE

GOLDEN_DIR = Path(__file__).resolve().parents[2] / "tui-toolkit" / "tests" / "golden"


def _capture(filename: str, render_callable):
    GOLDEN_DIR.mkdir(parents=True, exist_ok=True)
    rec = Console(record=True, width=80, force_terminal=True, color_system="truecolor")
    import controlplane_tool.console as ct_console
    original = ct_console.console
    ct_console.console = rec
    try:
        render_callable()
    finally:
        ct_console.console = original
    text = rec.export_text(styles=True)
    (GOLDEN_DIR / filename).write_text(text, encoding="utf-8")


def test_capture_completed():
    ctx = WorkflowContext()
    event = build_task_event(kind="task.completed", title="Build images", detail="2 of 2 ok", context=ctx)
    _capture("legacy_render_completed.txt", lambda: _render_event(event))


def test_capture_failed():
    ctx = WorkflowContext()
    event = build_task_event(kind="task.failed", title="Run E2E", detail="exit code 137", context=ctx)
    _capture("legacy_render_failed.txt", lambda: _render_event(event))


def test_capture_warning():
    ctx = WorkflowContext()
    event = build_task_event(kind="task.warning", title="Image not pinned", context=ctx)
    _capture("legacy_render_warning.txt", lambda: _render_event(event))


def test_capture_cancelled():
    ctx = WorkflowContext()
    event = build_task_event(kind="task.cancelled", title="Provision VM", detail="user cancel", context=ctx)
    _capture("legacy_render_cancelled.txt", lambda: _render_event(event))


def test_capture_phase():
    ctx = WorkflowContext()
    event = build_phase_event("Build phase", context=ctx)
    _capture("legacy_render_phase.txt", lambda: _render_event(event))


def test_capture_running_with_detail():
    ctx = WorkflowContext()
    event = build_task_event(kind="task.running", title="Compile", detail="java 21", context=ctx)
    _capture("legacy_render_running.txt", lambda: _render_event(event))


def test_capture_log_stdout():
    ctx = WorkflowContext()
    event = build_log_event(line="Hello, world", stream="stdout", context=ctx)
    _capture("legacy_render_log_stdout.txt", lambda: _render_event(event))


def test_capture_log_stderr():
    ctx = WorkflowContext()
    event = build_log_event(line="boom", stream="stderr", context=ctx)
    _capture("legacy_render_log_stderr.txt", lambda: _render_event(event))


def test_capture_questionary_style():
    """Serialize the legacy questionary _STYLE so DEFAULT_THEME parity can be verified."""
    GOLDEN_DIR.mkdir(parents=True, exist_ok=True)
    # _STYLE is a prompt_toolkit Style object with .style_rules directly accessible
    serialized_lines = []
    for selector, style_str in _STYLE.style_rules:
        serialized_lines.append(f"{selector}\t{style_str}")
    (GOLDEN_DIR / "legacy_questionary_style.txt").write_text(
        "\n".join(serialized_lines) + "\n", encoding="utf-8"
    )
