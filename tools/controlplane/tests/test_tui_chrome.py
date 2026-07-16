from __future__ import annotations

import ast
from contextlib import contextmanager
from io import StringIO
from pathlib import Path
from typing import Iterator

from prompt_toolkit.input import create_pipe_input
from prompt_toolkit.output import DummyOutput
from rich.console import Console

from controlplane_tool.tui.setup import NANOFAAS_BRAND, NANOFAAS_THEME
from controlplane_tool.tui.workflow import WorkflowDashboard
from tui_toolkit.context import UIContext, bind_ui
from tui_toolkit.pickers import Choice, _build_select_application


LOGO_SENTINEL = "███╗   ██╗"
SCREEN_COLUMNS = 120


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
    captures = [
        capture_picker(title="Main", breadcrumb="Main"),
        capture_picker(title="Validation", breadcrumb="Main / Validation"),
        capture_picker(title="Main", breadcrumb="Main"),
        capture_picker(title="Load Testing", breadcrumb="Main / Load Testing"),
    ]

    assert all(logo_line_count(capture) == 1 for capture in captures)
    assert all(capture.count(NANOFAAS_BRAND.ascii_logo) == 1 for capture in captures)


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
