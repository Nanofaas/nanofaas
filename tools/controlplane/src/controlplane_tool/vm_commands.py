from __future__ import annotations

import sys
from pathlib import Path

import typer
from pydantic import ValidationError

from tui_toolkit import fail, step
from tui_toolkit.console import console
from controlplane_tool.infra_flows import build_vm_flow
from controlplane_tool.paths import default_tool_paths
from controlplane_tool.prefect_runtime import run_local_flow
from controlplane_tool.vm_adapter import VmOrchestrator
from controlplane_tool.vm_models import VmRequest

VM_CONTEXT_SETTINGS = {
    "allow_extra_args": True,
    "ignore_unknown_options": True,
}

vm_app = typer.Typer(
    help="VM lifecycle orchestration commands.",
    no_args_is_help=True,
)


def _build_vm_request(
    *,
    lifecycle: str,
    name: str | None,
    host: str | None,
    user: str,
    home: str | None,
    cpus: int,
    memory: str,
    disk: str,
) -> VmRequest:
    return VmRequest(
        lifecycle=lifecycle,
        name=name,
        host=host,
        user=user,
        home=home,
        cpus=cpus,
        memory=memory,
        disk=disk,
    )


def _orchestrator() -> VmOrchestrator:
    return VmOrchestrator(default_tool_paths().workspace_root)


def _emit_result(result, *, dry_run: bool) -> None:
    if isinstance(result, list):
        for item in result:
            _emit_result(item, dry_run=dry_run)
        return

    if dry_run:
        console.print(" ".join(result.command))
        return

    if result.stdout:
        console.print(result.stdout.rstrip())
    if result.return_code != 0:
        if result.stderr:
            console.print(result.stderr.rstrip(), file=sys.stderr)
        raise typer.Exit(code=result.return_code)


def _execute(action) -> None:
    try:
        result = action()
    except ValidationError as exc:
        first_error = exc.errors()[0]["msg"] if exc.errors() else "validation failed"
        fail("Invalid VM request", first_error)
        raise typer.Exit(code=2)
    except ValueError as exc:
        fail("Invalid VM request", str(exc))
        raise typer.Exit(code=2)

    _emit_result(result["result"], dry_run=result["dry_run"])


def _run_flow_result(flow) -> object:  # noqa: ANN001
    flow_result = run_local_flow(flow.flow_id, flow.run)
    if flow_result.status != "completed" or flow_result.result is None:
        raise typer.Exit(code=1)
    return flow_result.result


def _vm_request_options(
    lifecycle: str,
    name: str | None,
    host: str | None,
    user: str,
    home: str | None,
    cpus: int,
    memory: str,
    disk: str,
) -> VmRequest:
    return _build_vm_request(
        lifecycle=lifecycle,
        name=name,
        host=host,
        user=user,
        home=home,
        cpus=cpus,
        memory=memory,
        disk=disk,
    )


@vm_app.command("up", context_settings=VM_CONTEXT_SETTINGS)
def vm_up(
    lifecycle: str = typer.Option("multipass", "--lifecycle"),
    name: str | None = typer.Option(None, "--name"),
    host: str | None = typer.Option(None, "--host"),
    user: str = typer.Option("ubuntu", "--user"),
    home: str | None = typer.Option(None, "--home"),
    cpus: int = typer.Option(4, "--cpus", min=1),
    memory: str = typer.Option("8G", "--memory"),
    disk: str = typer.Option("30G", "--disk"),
    dry_run: bool = typer.Option(False, "--dry-run"),
) -> None:
    def _action() -> dict[str, object]:
        request = _vm_request_options(lifecycle, name, host, user, home, cpus, memory, disk)
        flow = build_vm_flow(
            "vm.up",
            request=request,
            repo_root=default_tool_paths().workspace_root,
            dry_run=dry_run,
        )
        return {
            "dry_run": dry_run,
            "result": _run_flow_result(flow),
        }

    _execute(_action)


@vm_app.command("sync", context_settings=VM_CONTEXT_SETTINGS)
def vm_sync(
    lifecycle: str = typer.Option("multipass", "--lifecycle"),
    name: str | None = typer.Option(None, "--name"),
    host: str | None = typer.Option(None, "--host"),
    user: str = typer.Option("ubuntu", "--user"),
    home: str | None = typer.Option(None, "--home"),
    cpus: int = typer.Option(4, "--cpus", min=1),
    memory: str = typer.Option("8G", "--memory"),
    disk: str = typer.Option("30G", "--disk"),
    remote_dir: str | None = typer.Option(None, "--remote-dir"),
    dry_run: bool = typer.Option(False, "--dry-run"),
) -> None:
    def _action() -> dict[str, object]:
        request = _vm_request_options(lifecycle, name, host, user, home, cpus, memory, disk)
        flow = build_vm_flow(
            "vm.sync",
            request=request,
            repo_root=default_tool_paths().workspace_root,
            remote_dir=remote_dir,
            dry_run=dry_run,
        )
        return {
            "dry_run": dry_run,
            "result": _run_flow_result(flow),
        }

    _execute(_action)


@vm_app.command("provision-base", context_settings=VM_CONTEXT_SETTINGS)
def vm_provision_base(
    lifecycle: str = typer.Option("multipass", "--lifecycle"),
    name: str | None = typer.Option(None, "--name"),
    host: str | None = typer.Option(None, "--host"),
    user: str = typer.Option("ubuntu", "--user"),
    home: str | None = typer.Option(None, "--home"),
    cpus: int = typer.Option(4, "--cpus", min=1),
    memory: str = typer.Option("8G", "--memory"),
    disk: str = typer.Option("30G", "--disk"),
    install_helm: bool = typer.Option(False, "--install-helm"),
    helm_version: str = typer.Option("3.16.4", "--helm-version"),
    dry_run: bool = typer.Option(False, "--dry-run"),
) -> None:
    def _action() -> dict[str, object]:
        request = _vm_request_options(lifecycle, name, host, user, home, cpus, memory, disk)
        flow = build_vm_flow(
            "vm.provision_base",
            request=request,
            repo_root=default_tool_paths().workspace_root,
            install_helm=install_helm,
            helm_version=helm_version,
            dry_run=dry_run,
        )
        return {
            "dry_run": dry_run,
            "result": _run_flow_result(flow),
        }

    _execute(_action)


@vm_app.command("provision-k3s", context_settings=VM_CONTEXT_SETTINGS)
def vm_provision_k3s(
    lifecycle: str = typer.Option("multipass", "--lifecycle"),
    name: str | None = typer.Option(None, "--name"),
    host: str | None = typer.Option(None, "--host"),
    user: str = typer.Option("ubuntu", "--user"),
    home: str | None = typer.Option(None, "--home"),
    cpus: int = typer.Option(4, "--cpus", min=1),
    memory: str = typer.Option("8G", "--memory"),
    disk: str = typer.Option("30G", "--disk"),
    kubeconfig_path: str | None = typer.Option(None, "--kubeconfig-path"),
    k3s_version: str | None = typer.Option(None, "--k3s-version"),
    dry_run: bool = typer.Option(False, "--dry-run"),
) -> None:
    def _action() -> dict[str, object]:
        request = _vm_request_options(lifecycle, name, host, user, home, cpus, memory, disk)
        flow = build_vm_flow(
            "vm.provision_k3s",
            request=request,
            repo_root=default_tool_paths().workspace_root,
            kubeconfig_path=kubeconfig_path,
            k3s_version=k3s_version,
            dry_run=dry_run,
        )
        return {
            "dry_run": dry_run,
            "result": _run_flow_result(flow),
        }

    _execute(_action)


@vm_app.command("registry", context_settings=VM_CONTEXT_SETTINGS)
def vm_registry(
    lifecycle: str = typer.Option("multipass", "--lifecycle"),
    name: str | None = typer.Option(None, "--name"),
    host: str | None = typer.Option(None, "--host"),
    user: str = typer.Option("ubuntu", "--user"),
    home: str | None = typer.Option(None, "--home"),
    cpus: int = typer.Option(4, "--cpus", min=1),
    memory: str = typer.Option("8G", "--memory"),
    disk: str = typer.Option("30G", "--disk"),
    registry: str = typer.Option("localhost:5000", "--registry"),
    container_name: str = typer.Option("nanofaas-e2e-registry", "--container-name"),
    dry_run: bool = typer.Option(False, "--dry-run"),
) -> None:
    def _action() -> dict[str, object]:
        request = _vm_request_options(lifecycle, name, host, user, home, cpus, memory, disk)
        flow = build_vm_flow(
            "vm.registry",
            request=request,
            repo_root=default_tool_paths().workspace_root,
            registry=registry,
            container_name=container_name,
            dry_run=dry_run,
        )
        return {
            "dry_run": dry_run,
            "result": _run_flow_result(flow),
        }

    _execute(_action)


@vm_app.command("down", context_settings=VM_CONTEXT_SETTINGS)
def vm_down(
    lifecycle: str = typer.Option("multipass", "--lifecycle"),
    name: str | None = typer.Option(None, "--name"),
    host: str | None = typer.Option(None, "--host"),
    user: str = typer.Option("ubuntu", "--user"),
    home: str | None = typer.Option(None, "--home"),
    cpus: int = typer.Option(4, "--cpus", min=1),
    memory: str = typer.Option("8G", "--memory"),
    disk: str = typer.Option("30G", "--disk"),
    dry_run: bool = typer.Option(False, "--dry-run"),
) -> None:
    def _action() -> dict[str, object]:
        request = _vm_request_options(lifecycle, name, host, user, home, cpus, memory, disk)
        flow = build_vm_flow(
            "vm.down",
            request=request,
            repo_root=default_tool_paths().workspace_root,
            dry_run=dry_run,
        )
        return {
            "dry_run": dry_run,
            "result": _run_flow_result(flow),
        }

    _execute(_action)


@vm_app.command("inspect", context_settings=VM_CONTEXT_SETTINGS)
def vm_inspect(
    lifecycle: str = typer.Option("multipass", "--lifecycle"),
    name: str | None = typer.Option(None, "--name"),
    host: str | None = typer.Option(None, "--host"),
    user: str = typer.Option("ubuntu", "--user"),
    home: str | None = typer.Option(None, "--home"),
    cpus: int = typer.Option(4, "--cpus", min=1),
    memory: str = typer.Option("8G", "--memory"),
    disk: str = typer.Option("30G", "--disk"),
    dry_run: bool = typer.Option(False, "--dry-run"),
) -> None:
    def _action() -> dict[str, object]:
        request = _vm_request_options(lifecycle, name, host, user, home, cpus, memory, disk)
        flow = build_vm_flow(
            "vm.inspect",
            request=request,
            repo_root=default_tool_paths().workspace_root,
            dry_run=dry_run,
        )
        return {
            "dry_run": dry_run,
            "result": _run_flow_result(flow),
        }

    _execute(_action)


def install_vm_commands(app: typer.Typer) -> None:
    app.add_typer(vm_app, name="vm")
