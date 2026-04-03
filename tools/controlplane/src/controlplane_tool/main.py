from __future__ import annotations

import typer
from pydantic import ValidationError

from controlplane_tool.cli_commands import install_cli_commands
from controlplane_tool.pipeline import PipelineRunner, execute_pipeline
from controlplane_tool.paths import default_tool_paths
from controlplane_tool.profiles import load_profile
from controlplane_tool.tui import build_and_save_profile

app = typer.Typer(
    help="Control-plane orchestration product for build, test, and reporting."
)
DEFAULT_TOOL_PATHS = default_tool_paths()
DEFAULT_PROFILES_DIR = DEFAULT_TOOL_PATHS.profiles_dir.relative_to(
    DEFAULT_TOOL_PATHS.workspace_root
)


def _run_pipeline_entry(
    profile_name: str = typer.Option("default", help="Profile name to save/use."),
    use_saved_profile: bool = typer.Option(
        False,
        "--use-saved-profile",
        help=f"Load profile from {DEFAULT_PROFILES_DIR}/<name>.toml instead of opening wizard.",
    ),
) -> None:
    """Start Control plane interactive flow."""
    try:
        if use_saved_profile:
            profile = load_profile(profile_name)
            typer.echo(f"Loaded profile: {profile_name}")
        else:
            profile, destination = build_and_save_profile(profile_name=profile_name)
            typer.echo(f"Profile saved: {destination}")
    except FileNotFoundError:
        typer.echo(f"Profile not found: {profile_name}", err=True)
        raise typer.Exit(code=2)
    except ValidationError as exc:
        first_error = exc.errors()[0]["msg"] if exc.errors() else "validation failed"
        typer.echo(f"Invalid profile '{profile_name}': {first_error}", err=True)
        raise typer.Exit(code=2)

    result = execute_pipeline(profile, runner=PipelineRunner())
    typer.echo(f"Run status: {result.final_status}")
    typer.echo(f"Summary: {result.run_dir / 'summary.json'}")
    typer.echo(f"Report: {result.run_dir / 'report.html'}")
    if result.final_status != "passed":
        raise typer.Exit(code=1)


@app.command("pipeline-run")
def pipeline_run(
    profile_name: str = typer.Option("default", help="Profile name to save/use."),
    use_saved_profile: bool = typer.Option(
        False,
        "--use-saved-profile",
        help=f"Load profile from {DEFAULT_PROFILES_DIR}/<name>.toml instead of opening wizard.",
    ),
) -> None:
    _run_pipeline_entry(profile_name=profile_name, use_saved_profile=use_saved_profile)


@app.command("tui")
def tui(
    profile_name: str = typer.Option("default", help="Profile name to save/use."),
    use_saved_profile: bool = typer.Option(
        False,
        "--use-saved-profile",
        help=f"Load profile from {DEFAULT_PROFILES_DIR}/<name>.toml instead of opening wizard.",
    ),
) -> None:
    _run_pipeline_entry(profile_name=profile_name, use_saved_profile=use_saved_profile)


install_cli_commands(app)


def main() -> None:
    app()


if __name__ == "__main__":
    main()
