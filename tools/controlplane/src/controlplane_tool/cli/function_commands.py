from __future__ import annotations

from pathlib import Path

import typer
from rich.table import Table

from tui_toolkit.console import console
from controlplane_tool.functions.catalog import (
    list_function_presets,
    list_functions,
    resolve_function_definition,
    resolve_function_preset,
)
from controlplane_tool.app.paths import default_tool_paths

functions_app = typer.Typer(help="Function catalog and preset inspection commands.")


def _workspace_relative_path(path: Path) -> str:
    workspace_root = default_tool_paths().workspace_root.resolve()
    try:
        return path.resolve().relative_to(workspace_root).as_posix()
    except ValueError:
        return str(path)


@functions_app.command("list")
def functions_list() -> None:
    fn_table = Table(title="Functions", show_header=True, header_style="bold cyan")
    fn_table.add_column("Key", style="cyan")
    fn_table.add_column("Family")
    fn_table.add_column("Runtime", style="dim")
    for function in list_functions():
        fn_table.add_row(function.key, function.family or "", function.runtime or "")
    console.print(fn_table)

    console.print()

    preset_table = Table(title="Presets", show_header=True, header_style="bold cyan")
    preset_table.add_column("Name", style="cyan")
    preset_table.add_column("Description")
    preset_table.add_column("Functions", style="dim")
    for preset in list_function_presets():
        keys = ", ".join(function.key for function in preset.functions)
        preset_table.add_row(preset.name, preset.description or "", keys)
    console.print(preset_table)


@functions_app.command("show")
def functions_show(key: str = typer.Argument(..., help="Function key.")) -> None:
    function = resolve_function_definition(key)
    table = Table(show_header=False, box=None, padding=(0, 1))
    table.add_column("Field", style="bold cyan")
    table.add_column("Value")
    table.add_row("Key", function.key)
    table.add_row("Family", function.family or "")
    table.add_row("Runtime", function.runtime or "")
    table.add_row("Description", function.description or "")
    if function.example_dir is not None:
        table.add_row("Example Dir", _workspace_relative_path(function.example_dir))
    if function.default_image is not None:
        table.add_row("Default Image", function.default_image)
    if function.default_payload_file is not None:
        table.add_row("Default Payload", str(function.default_payload_file))
    console.print(table)


@functions_app.command("show-preset")
def functions_show_preset(name: str = typer.Argument(..., help="Preset name.")) -> None:
    preset = resolve_function_preset(name)
    console.print(f"[bold cyan]Preset:[/] {preset.name}")
    if preset.description:
        console.print(f"[dim]{preset.description}[/]")
    console.print()
    table = Table(show_header=True, header_style="bold cyan")
    table.add_column("Key", style="cyan")
    table.add_column("Family")
    table.add_column("Runtime", style="dim")
    for function in preset.functions:
        table.add_row(function.key, function.family or "", function.runtime or "")
    console.print(table)


def install_function_commands(app: typer.Typer) -> None:
    app.add_typer(functions_app, name="functions")
