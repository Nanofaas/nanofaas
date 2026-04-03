from __future__ import annotations

import typer
from pydantic import ValidationError

from controlplane_tool.pipeline import PipelineRunner
from controlplane_tool.profiles import load_profile, save_profile
from controlplane_tool.tui import build_profile_interactive

app = typer.Typer(
    help="Control-plane orchestration product for build, test, and reporting."
)


@app.command("run")
def run(
    profile_name: str = typer.Option("default", help="Profile name to save/use."),
    use_saved_profile: bool = typer.Option(
        False,
        "--use-saved-profile",
        help="Load profile from tooling/profiles/<name>.toml instead of opening wizard.",
    ),
) -> None:
    """Start Control plane interactive flow."""
    try:
        if use_saved_profile:
            profile = load_profile(profile_name)
            typer.echo(f"Loaded profile: {profile_name}")
        else:
            profile = build_profile_interactive(profile_name=profile_name)
            destination = save_profile(profile)
            typer.echo(f"Profile saved: {destination}")
    except FileNotFoundError:
        typer.echo(f"Profile not found: {profile_name}", err=True)
        raise typer.Exit(code=2)
    except ValidationError as exc:
        first_error = exc.errors()[0]["msg"] if exc.errors() else "validation failed"
        typer.echo(f"Invalid profile '{profile_name}': {first_error}", err=True)
        raise typer.Exit(code=2)

    result = PipelineRunner().run(profile)
    typer.echo(f"Run status: {result.final_status}")
    typer.echo(f"Summary: {result.run_dir / 'summary.json'}")
    typer.echo(f"Report: {result.run_dir / 'report.html'}")
    if result.final_status != "passed":
        raise typer.Exit(code=1)


def main() -> None:
    app()


if __name__ == "__main__":
    main()
