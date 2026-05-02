from __future__ import annotations

from controlplane_tool.shell_backend import ShellExecutionResult
from controlplane_tool.vm_adapter import VmOrchestrator
from controlplane_tool.vm_models import VmRequest


def ensure_vm_running_task(
    *,
    orchestrator: VmOrchestrator,
    request: VmRequest,
    dry_run: bool,
) -> ShellExecutionResult:
    return orchestrator.ensure_running(request, dry_run=dry_run)


def sync_project_task(
    *,
    orchestrator: VmOrchestrator,
    request: VmRequest,
    remote_dir: str | None = None,
    dry_run: bool,
) -> ShellExecutionResult:
    return orchestrator.sync_project(request, remote_dir=remote_dir, dry_run=dry_run)


def provision_base_task(
    *,
    orchestrator: VmOrchestrator,
    request: VmRequest,
    install_helm: bool,
    helm_version: str,
    dry_run: bool,
) -> ShellExecutionResult:
    return orchestrator.install_dependencies(
        request,
        install_helm=install_helm,
        helm_version=helm_version,
        dry_run=dry_run,
    )


def provision_k3s_task(
    *,
    orchestrator: VmOrchestrator,
    request: VmRequest,
    kubeconfig_path: str | None,
    k3s_version: str | None,
    dry_run: bool,
) -> ShellExecutionResult:
    return orchestrator.install_k3s(
        request,
        kubeconfig_path=kubeconfig_path,
        k3s_version=k3s_version,
        dry_run=dry_run,
    )


def ensure_registry_container_task(
    *,
    orchestrator: VmOrchestrator,
    request: VmRequest,
    registry: str,
    container_name: str,
    dry_run: bool,
) -> ShellExecutionResult:
    return orchestrator.ensure_registry_container(
        request,
        registry=registry,
        container_name=container_name,
        dry_run=dry_run,
    )


def configure_k3s_registry_task(
    *,
    orchestrator: VmOrchestrator,
    request: VmRequest,
    registry: str,
    dry_run: bool,
) -> ShellExecutionResult:
    return orchestrator.configure_k3s_registry(
        request,
        registry=registry,
        dry_run=dry_run,
    )


def teardown_vm_task(
    *,
    orchestrator: VmOrchestrator,
    request: VmRequest,
    dry_run: bool,
) -> ShellExecutionResult:
    return orchestrator.teardown(request, dry_run=dry_run)


def inspect_vm_task(
    *,
    orchestrator: VmOrchestrator,
    request: VmRequest,
    dry_run: bool,
) -> ShellExecutionResult:
    return orchestrator.inspect(request, dry_run=dry_run)
