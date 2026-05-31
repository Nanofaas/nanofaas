"""Tests for VmRequest proxmox lifecycle in controlplane context."""
from __future__ import annotations

import pytest


def test_proxmox_lifecycle_accepted_in_controlplane() -> None:
    from workflow_tasks.vm.models import VmRequest
    req = VmRequest(lifecycle="proxmox", proxmox_host="192.168.1.100", proxmox_node="pve")
    assert req.lifecycle == "proxmox"


def test_proxmox_orchestrator_importable() -> None:
    from controlplane_tool.infra.vm.proxmox_vm_adapter import ProxmoxVmOrchestrator
    assert ProxmoxVmOrchestrator is not None


def test_proxmox_vm_adapter_importable_from_lifecycle_adapters() -> None:
    from controlplane_tool.infra.vm_lifecycle_adapters import ProxmoxVmAdapter
    assert ProxmoxVmAdapter is not None
