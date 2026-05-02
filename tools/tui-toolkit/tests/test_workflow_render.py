"""Tests for tui_toolkit.workflow renderer + high-level helpers."""
from __future__ import annotations

from contextlib import contextmanager
from pathlib import Path

import pytest
from rich.console import Console

import tui_toolkit.console as console_mod
from tui_toolkit.context import UIContext, bind_ui
from tui_toolkit.events import WorkflowEvent
from tui_toolkit.theme import Theme
from tui_toolkit.workflow import (
    _render_event,
    bind_workflow_sink,
    build_log_event,
    build_phase_event,
    build_task_event,
    fail,
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


GOLDEN_DIR = Path(__file__).parent / "golden"


@pytest.fixture
def recording_console(monkeypatch):
    """Replace tui_toolkit.console.console with a recording one."""
    rec = Console(record=True, width=80, force_terminal=True, color_system="truecolor")
    monkeypatch.setattr(console_mod, "console", rec)
    return rec


def test_render_completed_matches_legacy_golden(recording_console):
    event = build_task_event(kind="task.completed", title="Build images", detail="2 of 2 ok")
    _render_event(event)
    actual = recording_console.export_text(styles=True)
    expected = (GOLDEN_DIR / "legacy_render_completed.txt").read_text(encoding="utf-8")
    assert actual == expected


def test_render_failed_matches_legacy_golden(recording_console):
    event = build_task_event(kind="task.failed", title="Run E2E", detail="exit code 137")
    _render_event(event)
    actual = recording_console.export_text(styles=True)
    expected = (GOLDEN_DIR / "legacy_render_failed.txt").read_text(encoding="utf-8")
    assert actual == expected


def test_render_warning_matches_legacy_golden(recording_console):
    event = build_task_event(kind="task.warning", title="Image not pinned")
    _render_event(event)
    actual = recording_console.export_text(styles=True)
    expected = (GOLDEN_DIR / "legacy_render_warning.txt").read_text(encoding="utf-8")
    assert actual == expected


def test_render_cancelled_matches_legacy_golden(recording_console):
    event = build_task_event(kind="task.cancelled", title="Provision VM", detail="user cancel")
    _render_event(event)
    actual = recording_console.export_text(styles=True)
    expected = (GOLDEN_DIR / "legacy_render_cancelled.txt").read_text(encoding="utf-8")
    assert actual == expected


def test_render_phase_matches_legacy_golden(recording_console):
    event = build_phase_event("Build phase")
    _render_event(event)
    actual = recording_console.export_text(styles=True)
    expected = (GOLDEN_DIR / "legacy_render_phase.txt").read_text(encoding="utf-8")
    assert actual == expected


def test_render_running_matches_legacy_golden(recording_console):
    event = build_task_event(kind="task.running", title="Compile", detail="java 21")
    _render_event(event)
    actual = recording_console.export_text(styles=True)
    expected = (GOLDEN_DIR / "legacy_render_running.txt").read_text(encoding="utf-8")
    assert actual == expected


def test_render_log_stdout_matches_legacy_golden(recording_console):
    event = build_log_event(line="Hello, world", stream="stdout")
    _render_event(event)
    actual = recording_console.export_text(styles=True)
    expected = (GOLDEN_DIR / "legacy_render_log_stdout.txt").read_text(encoding="utf-8")
    assert actual == expected


def test_render_log_stderr_matches_legacy_golden(recording_console):
    event = build_log_event(line="boom", stream="stderr")
    _render_event(event)
    actual = recording_console.export_text(styles=True)
    expected = (GOLDEN_DIR / "legacy_render_log_stderr.txt").read_text(encoding="utf-8")
    assert actual == expected


def test_render_uses_theme_icons(recording_console):
    """When the theme overrides icon_completed, the render uses the new glyph."""
    with bind_ui(UIContext(theme=Theme(icon_completed="OK"))):
        event = build_task_event(kind="task.completed", title="x")
        _render_event(event)
    out = recording_console.export_text(styles=False)
    assert "OK" in out
    assert "✓" not in out


def test_render_uses_theme_colors(recording_console):
    """When theme.success changes, the rendered style changes too."""
    with bind_ui(UIContext(theme=Theme(success="blue"))):
        event = build_task_event(kind="task.completed", title="x")
        _render_event(event)
    styled = recording_console.export_text(styles=True)
    # Rich converts named colors to ANSI escape codes; blue → \x1b[34m (ANSI code 34).
    assert "\x1b[34m" in styled


def test_phase_emits_event_to_active_sink():
    class CaptureSink:
        def __init__(self):
            self.events: list[WorkflowEvent] = []

        def emit(self, event):
            self.events.append(event)

        @contextmanager
        def status(self, label):
            yield

    sink = CaptureSink()
    with bind_workflow_sink(sink):
        phase("Build")
    assert len(sink.events) == 1
    assert sink.events[0].kind == "phase.started"
    assert sink.events[0].title == "Build"


def test_step_renders_to_console_when_no_sink(recording_console):
    step("Compile", detail="java 21")
    out = recording_console.export_text(styles=False)
    assert "Compile" in out
    assert "java 21" in out


def test_workflow_step_emits_running_then_completed():
    events = []

    class Sink:
        def emit(self, e):
            events.append(e)

        @contextmanager
        def status(self, label):
            yield

    with bind_workflow_sink(Sink()):
        with workflow_step(task_id="t1", title="run"):
            pass
    assert [e.kind for e in events] == ["task.running", "task.completed"]
    assert all(e.task_id == "t1" for e in events)


def test_workflow_step_emits_running_then_failed_on_exception():
    events = []

    class Sink:
        def emit(self, e):
            events.append(e)

        @contextmanager
        def status(self, label):
            yield

    with bind_workflow_sink(Sink()):
        with pytest.raises(RuntimeError):
            with workflow_step(task_id="t1", title="run"):
                raise RuntimeError("boom")
    assert [e.kind for e in events] == ["task.running", "task.failed"]
    assert events[1].detail == "boom"


def test_workflow_log_routes_to_sink():
    events = []

    class Sink:
        def emit(self, e):
            events.append(e)

        @contextmanager
        def status(self, label):
            yield

    with bind_workflow_sink(Sink()):
        workflow_log("hello", stream="stderr")
    assert len(events) == 1
    assert events[0].kind == "log.line"
    assert events[0].stream == "stderr"
    assert events[0].line == "hello"


def test_status_yields_when_no_sink(recording_console):
    with status("loading"):
        pass  # should not raise


def test_status_routes_to_sink_when_present():
    sink_started: list[str] = []

    class Sink:
        def emit(self, e):
            pass

        @contextmanager
        def status(self, label):
            sink_started.append(label)
            yield

    with bind_workflow_sink(Sink()):
        with status("loading"):
            pass
    assert sink_started == ["loading"]


def test_header_renders_with_brand(recording_console):
    from tui_toolkit.brand import AppBrand
    with bind_ui(UIContext(brand=AppBrand(ascii_logo="LOGO"))):
        header("subtitle")
    out = recording_console.export_text(styles=False)
    assert "LOGO" in out
    assert "subtitle" in out


def test_header_with_empty_brand_skips_logo(recording_console):
    from tui_toolkit.brand import AppBrand
    with bind_ui(UIContext(brand=AppBrand())):
        header("subtitle")
    out = recording_console.export_text(styles=False)
    assert "LOGO" not in out
    assert "subtitle" in out
