from __future__ import annotations

import typer

from controlplane_tool.function_catalog import (
    list_function_presets,
    list_functions,
    resolve_function_definition,
    resolve_function_preset,
)

functions_app = typer.Typer(help="Function catalog and preset inspection commands.")


@functions_app.command("list")
def functions_list() -> None:
    typer.echo("Functions:")
    for function in list_functions():
        typer.echo(f"{function.key}\t{function.family}\t{function.runtime}")

    typer.echo("")
    typer.echo("Presets:")
    for preset in list_function_presets():
        keys = ",".join(function.key for function in preset.functions)
        typer.echo(f"{preset.name}\t{preset.description}\t{keys}")


@functions_app.command("show")
def functions_show(key: str = typer.Argument(..., help="Function key.")) -> None:
    function = resolve_function_definition(key)
    typer.echo(f"Key: {function.key}")
    typer.echo(f"Family: {function.family}")
    typer.echo(f"Runtime: {function.runtime}")
    typer.echo(f"Description: {function.description}")
    if function.example_dir is not None:
        typer.echo(f"Example Dir: {function.example_dir}")
    if function.default_image is not None:
        typer.echo(f"Default Image: {function.default_image}")
    if function.default_payload_file is not None:
        typer.echo(f"Default Payload: {function.default_payload_file}")


@functions_app.command("show-preset")
def functions_show_preset(name: str = typer.Argument(..., help="Preset name.")) -> None:
    preset = resolve_function_preset(name)
    typer.echo(f"Preset: {preset.name}")
    typer.echo(f"Description: {preset.description}")
    typer.echo("Functions:")
    for function in preset.functions:
        typer.echo(f"{function.key}\t{function.family}\t{function.runtime}")


def install_function_commands(app: typer.Typer) -> None:
    app.add_typer(functions_app, name="functions")
