from __future__ import annotations

import typer
from pydantic import ValidationError

from controlplane_tool.cli_commands import install_cli_commands
from controlplane_tool.cli_test_commands import install_cli_test_commands
from controlplane_tool.e2e_commands import install_e2e_commands
from controlplane_tool.function_commands import install_function_commands
from controlplane_tool.loadtest_commands import (
    build_loadtest_request,
    install_loadtest_commands,
    run_loadtest_request,
)
from controlplane_tool.cli_e2e_commands import install_cli_e2e_commands
from controlplane_tool.k3s_e2e_commands import install_k3s_e2e_commands
from controlplane_tool.local_e2e_commands import install_local_e2e_commands
from controlplane_tool.paths import default_tool_paths
from controlplane_tool.profiles import load_profile
from controlplane_tool.tui import build_and_save_profile
from controlplane_tool.vm_commands import install_vm_commands

app = typer.Typer(
    help="Control plane orchestration product for build, test, and reporting."
)
DEFAULT_TOOL_PATHS = default_tool_paths()
DEFAULT_PROFILES_DIR = DEFAULT_TOOL_PATHS.profiles_dir.relative_to(
    DEFAULT_TOOL_PATHS.workspace_root
)


def _load_or_build_profile(
    profile_name: str = typer.Option("default", help="Profile name to save/use."),
    use_saved_profile: bool = typer.Option(
        False,
        "--use-saved-profile",
        help=f"Load profile from {DEFAULT_PROFILES_DIR}/<name>.toml instead of opening wizard.",
    ),
) -> tuple[object, str]:
    try:
        if use_saved_profile:
            profile = load_profile(profile_name)
            return profile, f"Loaded profile: {profile_name}"
        else:
            profile, destination = build_and_save_profile(profile_name=profile_name)
            return profile, f"Profile saved: {destination}"
    except FileNotFoundError:
        typer.echo(f"Profile not found: {profile_name}", err=True)
        raise typer.Exit(code=2)
    except ValidationError as exc:
        first_error = exc.errors()[0]["msg"] if exc.errors() else "validation failed"
        typer.echo(f"Invalid profile '{profile_name}': {first_error}", err=True)
        raise typer.Exit(code=2)


@app.command("tui")
def tui(
    profile_name: str = typer.Option("default", help="Profile name to save/use."),
    use_saved_profile: bool = typer.Option(
        False,
        "--use-saved-profile",
        help=f"Load profile from {DEFAULT_PROFILES_DIR}/<name>.toml instead of opening wizard.",
    ),
) -> None:
    profile, message = _load_or_build_profile(
        profile_name=profile_name,
        use_saved_profile=use_saved_profile,
    )
    typer.echo(message)
    run_loadtest_request(build_loadtest_request(profile=profile), dry_run=False)


install_cli_commands(app)
install_cli_test_commands(app)
install_vm_commands(app)
install_e2e_commands(app)
install_function_commands(app)
install_loadtest_commands(app)
install_local_e2e_commands(app)
install_cli_e2e_commands(app)
install_k3s_e2e_commands(app)


def main() -> None:
    app()


if __name__ == "__main__":
    main()
