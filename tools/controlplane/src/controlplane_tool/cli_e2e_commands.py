"""
cli_e2e_commands.py

CLI subcommands for VM-based CLI E2E scenario execution (M10).

These commands replace the shell backends deleted in M10.

Usage:
    controlplane-tool cli-e2e run vm             [--scenario-file PATH] [--skip-cli-build]
    controlplane-tool cli-e2e run host-platform  [--scenario-file PATH] [--skip-cli-build]
"""
from __future__ import annotations

import os
from pathlib import Path
from typing import Annotated, Optional

import typer

from controlplane_tool.paths import default_tool_paths

cli_e2e_app = typer.Typer(
    help="Run CLI E2E scenarios using the Python-native runtime (M10+).",
    no_args_is_help=True,
)

_run_app = typer.Typer(help="Run a specific CLI E2E scenario.", no_args_is_help=True)
cli_e2e_app.add_typer(_run_app, name="run")


@_run_app.command("vm")
def run_vm(
    scenario_file: Annotated[
        Optional[Path],
        typer.Option("--scenario-file", help="Resolved scenario manifest JSON path."),
    ] = None,
    namespace: Annotated[str, typer.Option(help="Kubernetes namespace.")] = "nanofaas-e2e",
    local_registry: Annotated[str, typer.Option(help="Local image registry.")] = "localhost:5000",
    skip_cli_build: Annotated[
        bool,
        typer.Option("--skip-cli-build", help="Skip :nanofaas-cli:installDist in VM."),
    ] = False,
) -> None:
    """Run the CLI workflow inside a VM-backed k3s environment (Python-native)."""
    from controlplane_tool.cli_runtime import CliVmRunner

    resolved_scenario_file = scenario_file or (
        Path(s) if (s := os.getenv("NANOFAAS_SCENARIO_PATH", "").strip()) else None
    )
    if os.getenv("NANOFAAS_CLI_SKIP_INSTALL_DIST", "").lower() == "true":
        skip_cli_build = True

    repo_root = default_tool_paths().workspace_root
    runner = CliVmRunner(
        repo_root,
        namespace=namespace,
        local_registry=local_registry,
        skip_cli_build=skip_cli_build,
    )
    try:
        runner.run(scenario_file=resolved_scenario_file)
    except Exception as exc:
        typer.echo(f"[e2e-cli] FAIL: {exc}", err=True)
        raise typer.Exit(code=1)


@_run_app.command("host-platform")
def run_host_platform(
    scenario_file: Annotated[
        Optional[Path],
        typer.Option("--scenario-file", help="Resolved scenario manifest JSON path."),
    ] = None,
    namespace: Annotated[str, typer.Option(help="Kubernetes namespace.")] = "nanofaas-host-cli-e2e",
    release: Annotated[str, typer.Option(help="Helm release name.")] = "nanofaas-host-cli-e2e",
    local_registry: Annotated[str, typer.Option(help="Local image registry.")] = "localhost:5000",
    skip_cli_build: Annotated[
        bool,
        typer.Option("--skip-cli-build", help="Skip :nanofaas-cli:installDist on host."),
    ] = False,
) -> None:
    """Run the host CLI platform lifecycle test against a VM-backed cluster (Python-native)."""
    from controlplane_tool.cli_runtime import CliHostPlatformRunner

    if os.getenv("NANOFAAS_CLI_SKIP_INSTALL_DIST", "").lower() == "true":
        skip_cli_build = True

    repo_root = default_tool_paths().workspace_root
    runner = CliHostPlatformRunner(
        repo_root,
        namespace=namespace,
        release=release,
        local_registry=local_registry,
        skip_cli_build=skip_cli_build,
    )
    try:
        runner.run(scenario_file=scenario_file)
    except Exception as exc:
        typer.echo(f"[e2e-host-cli] FAIL: {exc}", err=True)
        raise typer.Exit(code=1)


def install_cli_e2e_commands(app: typer.Typer) -> None:
    app.add_typer(cli_e2e_app, name="cli-e2e")
