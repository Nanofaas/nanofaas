from __future__ import annotations

import asyncio
import ast
from contextlib import contextmanager
from io import StringIO
from pathlib import Path
from typing import Iterator

from prompt_toolkit.input import create_pipe_input
from prompt_toolkit.output import DummyOutput
import pytest
from rich.console import Console

from controlplane_tool.tui.setup import NANOFAAS_BRAND, NANOFAAS_THEME
from controlplane_tool.tui.workflow import WorkflowDashboard
from tui_toolkit.context import UIContext, bind_ui
from tui_toolkit.pickers import Choice, _build_select_application


LOGO_SENTINEL = "███╗   ██╗"
SCREEN_COLUMNS = 120


class RecordingOutput(DummyOutput):
    """Record visible frames and alternate-screen boundaries from one session."""

    def __init__(self) -> None:
        self.enter_count = 0
        self.frames: list[str] = []
        self._active = False
        self._latest_frame: str | None = None
        self._screen_supplier = lambda: None

    def observe(self, app) -> None:
        self._screen_supplier = lambda: app.renderer.last_rendered_screen

    def enter_alternate_screen(self) -> None:
        self.enter_count += 1
        self._active = True
        self._latest_frame = None

    def quit_alternate_screen(self) -> None:
        assert self._latest_frame is not None
        self.frames.append(self._latest_frame)
        self._active = False

    def flush(self) -> None:
        screen = self._screen_supplier()
        if not self._active or screen is None:
            return
        self._latest_frame = "\n".join(
            "".join(
                screen.data_buffer[row][column].char for column in range(SCREEN_COLUMNS)
            ).rstrip()
            for row in range(screen.height)
        )


@contextmanager
def nanofaas_ui() -> Iterator[None]:
    with bind_ui(UIContext(theme=NANOFAAS_THEME, brand=NANOFAAS_BRAND)):
        yield


def capture_picker(*, title: str, breadcrumb: str) -> str:
    with nanofaas_ui(), create_pipe_input() as pipe_input:
        app = _build_select_application(
            "Choose an action",
            [Choice("Continue", "continue", "Open the selected screen")],
            default="continue",
            title=title,
            breadcrumb=breadcrumb,
            footer_hint="Esc back | Ctrl+C exit",
            input=pipe_input,
            output=DummyOutput(),
        )
        app.renderer.render(app, app.layout)
        screen = app.renderer.last_rendered_screen
        assert screen is not None
        return "\n".join(
            "".join(screen.data_buffer[row][column].char for column in range(SCREEN_COLUMNS)).rstrip()
            for row in range(screen.height)
        )


def capture_picker_sequence(*, full_screen: bool = True) -> RecordingOutput:
    output = RecordingOutput()
    screens = [
        ("Main", "Main"),
        ("Validation", "Main / Validation"),
        ("Main", "Main"),
        ("Load Testing", "Main / Load Testing"),
    ]
    with nanofaas_ui():
        for title, breadcrumb in screens:
            with create_pipe_input() as pipe_input:
                app = _build_select_application(
                    "Choose an action",
                    [Choice("Continue", "continue", "Open the selected screen")],
                    default="continue",
                    title=title,
                    breadcrumb=breadcrumb,
                    footer_hint="Esc back | Ctrl+C exit",
                    input=pipe_input,
                    output=output,
                )
                app.full_screen = full_screen
                app.renderer.full_screen = full_screen
                output.observe(app)
                def submit_after_first_paint() -> None:
                    asyncio.get_running_loop().call_later(0.01, pipe_input.send_text, "\r")

                app.run(pre_run=submit_after_first_paint)
    return output


def capture_dashboard(*, title: str = "Workflow") -> str:
    stream = StringIO()
    console = Console(
        file=stream,
        width=SCREEN_COLUMNS,
        force_terminal=True,
        color_system=None,
        record=True,
    )
    with nanofaas_ui():
        console.print(
            WorkflowDashboard(
                title=title,
                breadcrumb=f"Main / {title}",
                summary_lines=["Scenario: chrome regression"],
                planned_steps=["Run"],
            ).render()
        )
    return console.export_text(clear=False)


def logo_row(capture: str) -> int:
    return next(index for index, line in enumerate(capture.splitlines()) if LOGO_SENTINEL in line)


def logo_line_count(capture: str) -> int:
    return sum(LOGO_SENTINEL in line for line in capture.splitlines())


def assert_clean_navigation(session: RecordingOutput) -> None:
    expected_headers = [
        "NANOFAAS  Main",
        "NANOFAAS  Validation",
        "NANOFAAS  Main",
        "NANOFAAS  Load Testing",
    ]
    assert session.enter_count == 4
    assert len(session.frames) == 4
    assert all(logo_line_count(frame) == 1 for frame in session.frames)
    assert all(frame.count(NANOFAAS_BRAND.ascii_logo) == 1 for frame in session.frames)
    assert all(header in frame for header, frame in zip(expected_headers, session.frames, strict=True))
    assert "Validation" not in session.frames[2]
    assert "Validation" not in session.frames[3]
    assert "Load Testing" not in session.frames[0]
    assert "Load Testing" not in session.frames[1]
    assert "Load Testing" not in session.frames[2]


def test_main_picker_renders_the_nanofaas_logo_once() -> None:
    capture = capture_picker(title="Main", breadcrumb="Main")

    assert logo_line_count(capture) == 1


def test_submenu_picker_renders_the_nanofaas_logo_once() -> None:
    capture = capture_picker(title="Validation", breadcrumb="Main / Validation")

    assert logo_line_count(capture) == 1


def test_workflow_dashboard_renders_the_nanofaas_logo_once() -> None:
    capture = capture_dashboard()

    assert logo_line_count(capture) == 1


def test_menu_navigation_never_concatenates_previous_logo_chrome() -> None:
    session = capture_picker_sequence()

    assert_clean_navigation(session)


def test_navigation_harness_rejects_navigation_without_full_screen_replacement() -> None:
    session = capture_picker_sequence(full_screen=False)

    with pytest.raises(AssertionError):
        assert_clean_navigation(session)


def test_picker_and_dashboard_align_the_first_logo_line() -> None:
    menu_capture = capture_picker(title="Main", breadcrumb="Main")
    dashboard_capture = capture_dashboard(title="E2E")

    assert logo_row(menu_capture) == logo_row(dashboard_capture)


def test_controlplane_does_not_use_the_standalone_toolkit_header() -> None:
    source_root = Path(__file__).parents[1] / "src" / "controlplane_tool"

    for source_file in source_root.rglob("*.py"):
        tree = ast.parse(source_file.read_text(), filename=str(source_file))
        for node in ast.walk(tree):
            if isinstance(node, ast.ImportFrom) and node.module == "tui_toolkit.workflow":
                assert all(alias.name != "header" for alias in node.names), source_file
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
                assert not (
                    isinstance(node.func.value, ast.Name)
                    and node.func.value.id == "tui_toolkit"
                    and node.func.attr == "header"
                ), source_file
