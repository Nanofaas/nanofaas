from __future__ import annotations

from pathlib import Path


TASK_PACKAGE = Path(__file__).resolve().parents[1] / "src" / "controlplane_tool" / "tasks"

FORBIDDEN_IMPORT_TOKENS = (
    "controlplane_tool.core",
    "controlplane_tool.workflow",
    "controlplane_tool.tui",
    "controlplane_tool.app",
    "controlplane_tool.cli",
    "controlplane_tool.cli_validation",
    "controlplane_tool.orchestation",
    "shellcraft",
    "tui_toolkit",
    "typer",
    "questionary",
    "prefect",
    "multipass",
)


def test_task_package_contains_only_logic_modules() -> None:
    modules = sorted(path.name for path in TASK_PACKAGE.glob("*.py"))

    assert modules == [
        "__init__.py",
        "adapters.py",
        "executors.py",
        "models.py",
        "rendering.py",
    ]


def test_task_package_does_not_import_runtime_or_ui_boundaries() -> None:
    for path in TASK_PACKAGE.glob("*.py"):
        text = path.read_text(encoding="utf-8")
        for token in FORBIDDEN_IMPORT_TOKENS:
            assert token not in text, f"{path} imports or references {token}"
