from __future__ import annotations

from collections.abc import Mapping
from types import MappingProxyType

from controlplane_tool.paths import ToolPaths
from controlplane_tool.scenario_components.environment import ScenarioExecutionContext
from controlplane_tool.scenario_components.models import ScenarioComponentDefinition
from controlplane_tool.scenario_components.operations import RemoteCommandOperation, ScenarioOperation


def _remote_home(vm_request) -> str:
    if vm_request.home:
        return vm_request.home
    if vm_request.user == "root":
        return "/root"
    return f"/home/{vm_request.user}"


def _remote_project_dir(vm_request) -> str:
    return f"{_remote_home(vm_request)}/nanofaas"


def _kubeconfig_path(vm_request) -> str:
    return f"{_remote_home(vm_request)}/.kube/config"


def _inventory_target(vm_request) -> str:
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

    command: list[str] = [
        "ansible-playbook",
        "-i",
        _inventory_target(context.vm_request),
        "-u",
        context.vm_request.user,
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
                argv=(
                    "rsync",
                    "-az",
                    "--delete",
                    f"{context.repo_root}/",
                    f"{vm_request.user}@{vm_request.host}:{destination}/",
                ),
            ),
        )

    return (
        RemoteCommandOperation(
            operation_id="repo.sync_to_vm",
            summary="Sync repository into VM",
            argv=("multipass", "transfer", "-r", str(context.repo_root), f"{vm_request.name or 'nanofaas-e2e'}:{destination}"),
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
