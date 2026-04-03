from __future__ import annotations

from pathlib import Path

import typer
from pydantic import ValidationError

from controlplane_tool.paths import default_tool_paths
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
    if dry_run:
        typer.echo(" ".join(result.command))
        return

    if result.stdout:
        typer.echo(result.stdout.rstrip())
    if result.return_code != 0:
        if result.stderr:
            typer.echo(result.stderr.rstrip(), err=True)
        raise typer.Exit(code=result.return_code)


def _execute(action) -> None:
    try:
        result = action()
    except ValidationError as exc:
        first_error = exc.errors()[0]["msg"] if exc.errors() else "validation failed"
        typer.echo(f"Invalid VM request: {first_error}", err=True)
        raise typer.Exit(code=2)
    except ValueError as exc:
        typer.echo(f"Invalid VM request: {exc}", err=True)
        raise typer.Exit(code=2)

    _emit_result(result["result"], dry_run=result["dry_run"])


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
        return {
            "dry_run": dry_run,
            "result": _orchestrator().ensure_running(request, dry_run=dry_run),
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
        return {
            "dry_run": dry_run,
            "result": _orchestrator().sync_project(
                request,
                remote_dir=remote_dir,
                dry_run=dry_run,
            ),
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
        return {
            "dry_run": dry_run,
            "result": _orchestrator().install_dependencies(
                request,
                install_helm=install_helm,
                helm_version=helm_version,
                dry_run=dry_run,
            ),
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
        return {
            "dry_run": dry_run,
            "result": _orchestrator().install_k3s(
                request,
                kubeconfig_path=kubeconfig_path,
                k3s_version=k3s_version,
                dry_run=dry_run,
            ),
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
        return {
            "dry_run": dry_run,
            "result": _orchestrator().setup_registry(
                request,
                registry=registry,
                container_name=container_name,
                dry_run=dry_run,
            ),
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
        return {
            "dry_run": dry_run,
            "result": _orchestrator().teardown(request, dry_run=dry_run),
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
        return {
            "dry_run": dry_run,
            "result": _orchestrator().inspect(request, dry_run=dry_run),
        }

    _execute(_action)


def install_vm_commands(app: typer.Typer) -> None:
    app.add_typer(vm_app, name="vm")
