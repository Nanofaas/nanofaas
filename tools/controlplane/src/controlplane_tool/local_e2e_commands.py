"""
local_e2e_commands.py

CLI subcommands for local E2E scenario execution (Python-native path).

These commands replace the shell backends deleted in M9.  They are invoked
as subprocesses by E2eRunner plan steps, but can also be called directly.

Usage (via controlplane.sh):
    controlplane-tool local-e2e run container-local [--scenario-file PATH]
    controlplane-tool local-e2e run deploy-host     [--scenario-file PATH]
"""
from __future__ import annotations

import os
from pathlib import Path
from typing import Annotated, Optional

import typer

from controlplane_tool.paths import default_tool_paths

local_e2e_app = typer.Typer(
    help="Run local E2E scenarios using the Python-native runtime (M9+).",
    no_args_is_help=True,
)

run_app = typer.Typer(help="Run a specific local E2E scenario.", no_args_is_help=True)
local_e2e_app.add_typer(run_app, name="run")


@run_app.command("container-local")
def run_container_local(
    scenario_file: Annotated[
        Optional[Path],
        typer.Option("--scenario-file", help="Resolved scenario manifest JSON path."),
    ] = None,
    api_port: Annotated[int, typer.Option(help="Control-plane API port.")] = 18080,
    mgmt_port: Annotated[int, typer.Option(help="Control-plane management port.")] = 18081,
    runtime_adapter: Annotated[
        Optional[str],
        typer.Option(help="Container runtime adapter (docker/podman/nerdctl)."),
    ] = None,
    modules: Annotated[str, typer.Option(help="Control-plane modules.")] = "container-deployment-provider",
) -> None:
    """Run the container-local managed DEPLOYMENT E2E flow (Python-native)."""
    from controlplane_tool.local_e2e_runner import ContainerLocalE2eRunner

    # Allow overriding scenario path via env (matches legacy NANOFAAS_SCENARIO_PATH convention)
    resolved_scenario_file = scenario_file or (
        Path(s) if (s := os.getenv("NANOFAAS_SCENARIO_PATH", "").strip()) else None
    )

    repo_root = default_tool_paths().workspace_root
    runner = ContainerLocalE2eRunner(
        repo_root,
        api_port=api_port,
        mgmt_port=mgmt_port,
        runtime_adapter=runtime_adapter,
        control_plane_modules=modules,
    )
    try:
        runner.run(scenario_file=resolved_scenario_file)
    except Exception as exc:
        typer.echo(f"[e2e-container-local] FAIL: {exc}", err=True)
        raise typer.Exit(code=1)


@run_app.command("deploy-host")
def run_deploy_host(
    scenario_file: Annotated[
        Optional[Path],
        typer.Option("--scenario-file", help="Resolved scenario manifest JSON path."),
    ] = None,
    registry_port: Annotated[int, typer.Option(help="Local registry port.")] = 5050,
    control_plane_port: Annotated[int, typer.Option(help="Fake control-plane port.")] = 18080,
    skip_cli_build: Annotated[
        bool,
        typer.Option("--skip-cli-build", help="Skip :nanofaas-cli:installDist if CLI already built."),
    ] = False,
) -> None:
    """Run the deploy-host E2E flow (Python-native)."""
    from controlplane_tool.local_e2e_runner import DeployHostE2eRunner

    resolved_scenario_file = scenario_file or (
        Path(s) if (s := os.getenv("NANOFAAS_SCENARIO_PATH", "").strip()) else None
    )
    if os.getenv("NANOFAAS_CLI_SKIP_INSTALL_DIST", "").lower() == "true":
        skip_cli_build = True

    repo_root = default_tool_paths().workspace_root
    runner = DeployHostE2eRunner(
        repo_root,
        registry_port=registry_port,
        control_plane_port=control_plane_port,
    )
    try:
        runner.run(scenario_file=resolved_scenario_file, skip_cli_build=skip_cli_build)
    except Exception as exc:
        typer.echo(f"[e2e-deploy-host] FAIL: {exc}", err=True)
        raise typer.Exit(code=1)


def install_local_e2e_commands(app: typer.Typer) -> None:
    app.add_typer(local_e2e_app, name="local-e2e")
