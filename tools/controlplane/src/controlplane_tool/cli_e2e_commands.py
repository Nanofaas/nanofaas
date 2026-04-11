"""
cli_e2e_commands.py

CLI subcommands for VM-based CLI E2E scenario execution (M10).

These commands replace the shell backends deleted in M10.

Usage:
    controlplane-tool cli-e2e run vm             [--scenario-file PATH] [--skip-cli-build]
    controlplane-tool cli-e2e run cli-stack      [--scenario-file PATH] [--skip-cli-build]
    controlplane-tool cli-e2e run host-platform  [--scenario-file PATH] [--skip-cli-build]
"""
from __future__ import annotations

import os
from pathlib import Path
from typing import Annotated, Optional

import typer

from controlplane_tool.flow_catalog import resolve_flow_definition
from controlplane_tool.paths import default_tool_paths, scenario_path_from_env
from controlplane_tool.prefect_runtime import run_local_flow

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

    resolved_scenario_file = scenario_path_from_env(scenario_file)
    if os.getenv("NANOFAAS_CLI_SKIP_INSTALL_DIST", "").lower() == "true":
        skip_cli_build = True

    repo_root = default_tool_paths().workspace_root
    flow = resolve_flow_definition(
        "e2e.cli",
        repo_root=repo_root,
        scenario_file=resolved_scenario_file,
        namespace=namespace,
        local_registry=local_registry,
        skip_cli_build=skip_cli_build,
    )
    result = run_local_flow(flow.flow_id, flow.run)
    if result.status != "completed":
        typer.echo(f"[e2e-cli] FAIL: {result.error or result.status}", err=True)
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
    flow = resolve_flow_definition(
        "e2e.cli-host",
        repo_root=repo_root,
        scenario_file=scenario_file,
        namespace=namespace,
        local_registry=local_registry,
        release=release,
        skip_cli_build=skip_cli_build,
    )
    result = run_local_flow(flow.flow_id, flow.run)
    if result.status != "completed":
        typer.echo(f"[e2e-host-cli] FAIL: {result.error or result.status}", err=True)
        raise typer.Exit(code=1)


@_run_app.command("cli-stack")
def run_cli_stack(
    scenario_file: Annotated[
        Optional[Path],
        typer.Option("--scenario-file", help="Resolved scenario manifest JSON path."),
    ] = None,
    namespace: Annotated[str, typer.Option(help="Kubernetes namespace.")] = "nanofaas-cli-stack-e2e",
    local_registry: Annotated[str, typer.Option(help="Local image registry.")] = "localhost:5000",
    skip_cli_build: Annotated[
        bool,
        typer.Option("--skip-cli-build", help="Skip :nanofaas-cli:installDist in VM."),
    ] = False,
) -> None:
    """Run the dedicated VM-backed CLI stack workflow (Python-native)."""
    resolved_scenario_file = scenario_path_from_env(scenario_file)
    if os.getenv("NANOFAAS_CLI_SKIP_INSTALL_DIST", "").lower() == "true":
        skip_cli_build = True

    repo_root = default_tool_paths().workspace_root
    flow = resolve_flow_definition(
        "e2e.cli-stack",
        repo_root=repo_root,
        scenario_file=resolved_scenario_file,
        namespace=namespace,
        local_registry=local_registry,
        skip_cli_build=skip_cli_build,
    )
    result = run_local_flow(flow.flow_id, flow.run)
    if result.status != "completed":
        typer.echo(f"[e2e-cli-stack] FAIL: {result.error or result.status}", err=True)
        raise typer.Exit(code=1)


def install_cli_e2e_commands(app: typer.Typer) -> None:
    app.add_typer(cli_e2e_app, name="cli-e2e")
