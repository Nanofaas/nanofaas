from __future__ import annotations

from collections.abc import Mapping
from types import MappingProxyType

from multipass import find_ssh_public_key

from controlplane_tool.workspace.paths import ToolPaths
from controlplane_tool.scenario.components.environment import ScenarioExecutionContext
from controlplane_tool.scenario.components.models import ScenarioComponentDefinition
from controlplane_tool.scenario.components.operations import RemoteCommandOperation, ScenarioOperation
from controlplane_tool.infra.vm.vm_adapter import (
    _find_ssh_private_key_path,
    repo_rsync_command,
    repo_sync_ssh_rsh,
)
from controlplane_tool.infra.vm.vm_models import VmRequest


def _remote_home(vm_request: VmRequest) -> str:
    if vm_request.home:
        return vm_request.home
    if vm_request.user == "root":
        return "/root"
    return f"/home/{vm_request.user}"


def _remote_project_dir(vm_request: VmRequest) -> str:
    return f"{_remote_home(vm_request)}/nanofaas"


def _kubeconfig_path(vm_request: VmRequest) -> str:
    return f"{_remote_home(vm_request)}/.kube/config"


def _inventory_target(vm_request: VmRequest) -> str:
    if vm_request.lifecycle == "external":
        if vm_request.host is None:
            raise ValueError("external VM lifecycle requires a host")
        return f"{vm_request.host},"
    return f"<multipass-ip:{vm_request.name or 'nanofaas-e2e'}>,"


def _frozen_env(env: Mapping[str, str] | None = None) -> Mapping[str, str]:
    return MappingProxyType(dict(env or {}))


def _ansible_operation(
    *,
    context: ScenarioExecutionContext,
    operation_id: str,
    summary: str,
    playbook_name: str,
    extra_vars: Mapping[str, str],
) -> RemoteCommandOperation:
    paths = ToolPaths.repo_root(context.repo_root)
    extra_args: list[str] = []
    for key, value in extra_vars.items():
        extra_args.extend(["-e", f"{key}={value}"])

    private_key = _find_ssh_private_key_path(find_ssh_public_key())
    private_key_args: list[str] = ["--private-key", str(private_key)] if private_key is not None else []

    command: list[str] = [
        "ansible-playbook",
        "-i",
        _inventory_target(context.vm_request),
        "-u",
        context.vm_request.user,
        *private_key_args,
        *extra_args,
        str(paths.ansible_root / "playbooks" / playbook_name),
    ]
    return RemoteCommandOperation(
        operation_id=operation_id,
        summary=summary,
        argv=tuple(command),
        env=_frozen_env({"ANSIBLE_CONFIG": str(paths.ansible_root / "ansible.cfg")}),
    )


def plan_vm_ensure_running(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    vm_request = context.vm_request
    if vm_request.lifecycle == "external":
        return (
            RemoteCommandOperation(
                operation_id="vm.ensure_running",
                summary="Ensure VM is running",
                argv=("ssh", f"{vm_request.user}@{vm_request.host}", "true"),
            ),
        )

    return (
        RemoteCommandOperation(
            operation_id="vm.ensure_running",
            summary="Ensure VM is running",
            argv=(
                "multipass",
                "launch",
                "--name",
                vm_request.name or "nanofaas-e2e",
                "--cpus",
                str(vm_request.cpus),
                "--memory",
                vm_request.memory,
                "--disk",
                vm_request.disk,
            ),
        ),
    )


def plan_vm_provision_base(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    return (
        _ansible_operation(
            context=context,
            operation_id="vm.provision_base",
            summary="Provision base VM dependencies",
            playbook_name="provision-base.yml",
            extra_vars={
                "install_helm": "true",
                "helm_version": "3.16.4",
                "vm_user": context.vm_request.user,
            },
        ),
    )


def plan_repo_sync_to_vm(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    vm_request = context.vm_request
    destination = _remote_project_dir(vm_request)
    if vm_request.lifecycle == "external":
        if vm_request.host is None:
            raise ValueError("external VM lifecycle requires a host")
        return (
            RemoteCommandOperation(
                operation_id="repo.sync_to_vm",
                summary="Sync repository into VM",
                argv=tuple(
                    repo_rsync_command(
                        source=context.repo_root,
                        user=vm_request.user,
                        host=vm_request.host,
                        destination=destination,
                    )
                ),
            ),
        )

    return (
        RemoteCommandOperation(
            operation_id="repo.sync_to_vm",
            summary="Sync repository into VM",
            argv=tuple(
                repo_rsync_command(
                    source=context.repo_root,
                    user=vm_request.user,
                    host=f"<multipass-ip:{vm_request.name or 'nanofaas-e2e'}>",
                    destination=destination,
                    ssh_rsh=repo_sync_ssh_rsh(
                        _find_ssh_private_key_path(find_ssh_public_key())
                    ),
                )
            ),
        ),
    )


def plan_registry_ensure_container(
    context: ScenarioExecutionContext,
) -> tuple[ScenarioOperation, ...]:
    registry_host, registry_port = context.local_registry.rsplit(":", 1)
    return (
        _ansible_operation(
            context=context,
            operation_id="registry.ensure_container",
            summary="Ensure local registry container is running",
            playbook_name="ensure-registry.yml",
            extra_vars={
                "registry": context.local_registry,
                "registry_host": registry_host,
                "registry_port": registry_port,
                "registry_container_name": "nanofaas-e2e-registry",
            },
        ),
    )


def plan_k3s_install(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    vm_request = context.vm_request
    return (
        _ansible_operation(
            context=context,
            operation_id="k3s.install",
            summary="Install k3s",
            playbook_name="provision-k3s.yml",
            extra_vars={
                "vm_user": vm_request.user,
                "kubeconfig_path": _kubeconfig_path(vm_request),
            },
        ),
    )


def plan_k3s_configure_registry(
    context: ScenarioExecutionContext,
) -> tuple[ScenarioOperation, ...]:
    registry_host, registry_port = context.local_registry.rsplit(":", 1)
    return (
        _ansible_operation(
            context=context,
            operation_id="k3s.configure_registry",
            summary="Configure k3s registry access",
            playbook_name="configure-k3s-registry.yml",
            extra_vars={
                "registry": context.local_registry,
                "registry_host": registry_host,
                "registry_port": registry_port,
            },
        ),
    )


def plan_loadtest_install_k6(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    return (
        _ansible_operation(
            context=context,
            operation_id="loadtest.install_k6",
            summary="Install k6 for load testing",
            playbook_name="install-k6.yml",
            extra_vars={},
        ),
    )


VM_ENSURE_RUNNING = ScenarioComponentDefinition(
    component_id="vm.ensure_running",
    summary="Ensure VM is running",
    planner=plan_vm_ensure_running,
)

VM_PROVISION_BASE = ScenarioComponentDefinition(
    component_id="vm.provision_base",
    summary="Provision base VM dependencies",
    planner=plan_vm_provision_base,
)

REPO_SYNC_TO_VM = ScenarioComponentDefinition(
    component_id="repo.sync_to_vm",
    summary="Sync repository into VM",
    planner=plan_repo_sync_to_vm,
)

REGISTRY_ENSURE_CONTAINER = ScenarioComponentDefinition(
    component_id="registry.ensure_container",
    summary="Ensure local registry container is running",
    planner=plan_registry_ensure_container,
)

K3S_INSTALL = ScenarioComponentDefinition(
    component_id="k3s.install",
    summary="Install k3s",
    planner=plan_k3s_install,
)

K3S_CONFIGURE_REGISTRY = ScenarioComponentDefinition(
    component_id="k3s.configure_registry",
    summary="Configure k3s registry access",
    planner=plan_k3s_configure_registry,
)

LOADTEST_INSTALL_K6 = ScenarioComponentDefinition(
    component_id="loadtest.install_k6",
    summary="Install k6 for load testing",
    planner=plan_loadtest_install_k6,
)
