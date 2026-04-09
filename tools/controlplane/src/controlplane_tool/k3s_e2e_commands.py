"""
k3s_e2e_commands.py

CLI subcommands for VM/K3s-backed E2E scenario execution (M11).

These commands replace the shell backends deleted in M11.

Usage:
    controlplane-tool k3s-e2e run k3s-curl   [--scenario-file PATH]
    controlplane-tool k3s-e2e run helm-stack
"""
from __future__ import annotations

import os
from pathlib import Path
from typing import Annotated, Optional

import typer

from controlplane_tool.paths import default_tool_paths, scenario_path_from_env
from controlplane_tool.prefect_runtime import run_local_flow
from controlplane_tool.scenario_flows import build_scenario_flow

k3s_e2e_app = typer.Typer(
    help="Run VM/K3s-backed E2E scenarios using the Python-native runtime (M11+).",
    no_args_is_help=True,
)

_run_app = typer.Typer(help="Run a specific K3s E2E scenario.", no_args_is_help=True)
k3s_e2e_app.add_typer(_run_app, name="run")


@_run_app.command("k3s-curl")
def run_k3s_curl(
    scenario_file: Annotated[
        Optional[Path],
        typer.Option("--scenario-file", help="Resolved scenario manifest JSON path."),
    ] = None,
    namespace: Annotated[str, typer.Option(help="Kubernetes namespace.")] = "nanofaas-e2e",
    local_registry: Annotated[str, typer.Option(help="Local image registry.")] = "localhost:5000",
    runtime: Annotated[str, typer.Option(help="Control-plane runtime.")] = "java",
) -> None:
    """Run the k3s curl compatibility workflow inside a VM-backed environment (Python-native)."""
    from controlplane_tool.k3s_runtime import K3sCurlRunner

    resolved_scenario_file = scenario_path_from_env(scenario_file)
    resolved_runtime = os.getenv("CONTROL_PLANE_RUNTIME", runtime)

    repo_root = default_tool_paths().workspace_root
    flow = build_scenario_flow(
        "k3s-curl",
        repo_root=repo_root,
        scenario_file=resolved_scenario_file,
        namespace=namespace,
        local_registry=local_registry,
        runtime=resolved_runtime,
    )
    result = run_local_flow(flow.flow_id, flow.run)
    if result.status != "completed":
        typer.echo(f"[k3s-curl] FAIL: {result.error or result.status}", err=True)
        raise typer.Exit(code=1)


@_run_app.command("helm-stack")
def run_helm_stack(
    namespace: Annotated[str, typer.Option(help="Kubernetes namespace.")] = "nanofaas",
    local_registry: Annotated[str, typer.Option(help="Local image registry.")] = "localhost:5000",
    runtime: Annotated[str, typer.Option(help="Control-plane runtime.")] = "java",
) -> None:
    """Run the Helm stack compatibility workflow against a VM-backed cluster (Python-native)."""
    from controlplane_tool.k3s_runtime import HelmStackRunner

    resolved_runtime = os.getenv("CONTROL_PLANE_RUNTIME", runtime)
    noninteractive = os.getenv("E2E_K3S_HELM_NONINTERACTIVE", "").lower() == "true"

    repo_root = default_tool_paths().workspace_root
    flow = build_scenario_flow(
        "helm-stack",
        repo_root=repo_root,
        namespace=namespace,
        local_registry=local_registry,
        runtime=resolved_runtime,
        noninteractive=noninteractive,
    )
    result = run_local_flow(flow.flow_id, flow.run)
    if result.status != "completed":
        typer.echo(f"[helm-stack] FAIL: {result.error or result.status}", err=True)
        raise typer.Exit(code=1)


def install_k3s_e2e_commands(app: typer.Typer) -> None:
    app.add_typer(k3s_e2e_app, name="k3s-e2e")
