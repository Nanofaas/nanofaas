from __future__ import annotations

import os
from pathlib import Path

from workflow_tasks import VmRequest
from workflow_tasks.vm.azure import AzureVmProvider
from workflow_tasks.vm.proxmox import ProxmoxVmProvider

from controlplane_tool.config.environment import EnvironmentConfig, ExecutionRole


_DEFAULT_NAMES = {
    ("azure", "stack"): "nanofaas-azure",
    ("azure", "loadgen"): "nanofaas-azure-loadgen",
    ("azure", "arm-builder"): "nanofaas-azure-arm",
    ("proxmox", "stack"): "nanofaas-proxmox",
    ("proxmox", "loadgen"): "nanofaas-proxmox-loadgen",
}


def vm_provider_for_environment(environment: EnvironmentConfig, repo_root: Path) -> object:
    if environment.provider == "azure":
        return AzureVmProvider(repo_root)
    if environment.provider == "proxmox":
        return ProxmoxVmProvider(repo_root)
    raise ValueError(f"{environment.provider} does not use a cloud VM provider")


def vm_request_for_role(
    environment: EnvironmentConfig,
    role: ExecutionRole,
    *,
    loadtest: bool = False,
) -> VmRequest:
    provider = environment.provider
    if provider == "local":
        raise ValueError("local environments do not have a VM request")

    target = environment.target(role)
    user = target.user
    if provider == "azure" and "user" not in target.model_fields_set:
        user = "azureuser"

    common = {
        "lifecycle": provider,
        "name": target.name or _DEFAULT_NAMES.get((provider, role)),
        "host": target.host,
        "user": user,
        "home": target.home,
        "cpus": target.cpus,
        "memory": target.memory,
        "disk": target.disk,
    }
    if provider == "azure":
        azure = environment.azure
        assert azure is not None
        if role == "loadgen":
            vm_size = azure.loadgen_vm_size
        elif role == "arm-builder":
            vm_size = azure.arm_vm_size
        else:
            vm_size = azure.vm_size
        return VmRequest(
            **common,
            azure_vm_size=vm_size,
            azure_resource_group=azure.resource_group,
            azure_location=azure.location,
            azure_image_urn=(
                azure.arm_image_urn if role == "arm-builder" else azure.image_urn
            ),
            azure_ssh_key_path=azure.ssh_key_path,
            azure_open_ports=(
                (30080, 30081, 30090)
                if loadtest and role == "stack" and azure.operator_source_cidr is None
                else None
            ),
        )
    if provider == "proxmox":
        proxmox = environment.proxmox
        assert proxmox is not None
        return VmRequest(
            **common,
            proxmox_host=proxmox.host,
            proxmox_node=proxmox.node,
            proxmox_user=proxmox.user,
            proxmox_password=os.getenv(proxmox.password_env),
            proxmox_template_id=proxmox.template_id,
            proxmox_ssh_key_path=proxmox.ssh_key_path,
        )
    return VmRequest(**common)
