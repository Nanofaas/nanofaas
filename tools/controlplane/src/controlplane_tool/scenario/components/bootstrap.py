# Shim: re-exports from workflow_tasks.components.bootstrap (migrated in sub-project 2b.4).
from __future__ import annotations

from workflow_tasks.components.bootstrap import (
    K3S_CONFIGURE_REGISTRY,
    K3S_INSTALL,
    LOADTEST_INSTALL_K6,
    REGISTRY_ENSURE_CONTAINER,
    REPO_SYNC_TO_VM,
    VM_ENSURE_RUNNING,
    VM_PROVISION_BASE,
    plan_k3s_configure_registry,
    plan_k3s_install,
    plan_loadtest_install_k6,
    plan_registry_ensure_container,
    plan_repo_sync_to_vm,
    plan_vm_ensure_running,
    plan_vm_provision_base,
)

__all__ = [
    "K3S_CONFIGURE_REGISTRY",
    "K3S_INSTALL",
    "LOADTEST_INSTALL_K6",
    "REGISTRY_ENSURE_CONTAINER",
    "REPO_SYNC_TO_VM",
    "VM_ENSURE_RUNNING",
    "VM_PROVISION_BASE",
    "plan_k3s_configure_registry",
    "plan_k3s_install",
    "plan_loadtest_install_k6",
    "plan_registry_ensure_container",
    "plan_repo_sync_to_vm",
    "plan_vm_ensure_running",
    "plan_vm_provision_base",
]
