from __future__ import annotations

from unittest.mock import MagicMock

from workflow_tasks.vm.adapters import AzureVmAdapter, MultipassVmAdapter, VmLifecycleAdapter
from workflow_tasks.vm.models import VmConfig, VmInfo


def _make_orchestrator(host: str = "10.0.0.1") -> MagicMock:
    orch = MagicMock()
    orch.connection_host.return_value = host
    return orch


def test_vm_lifecycle_adapter_ensure_running_returns_vm_info() -> None:
    orch = _make_orchestrator("192.168.1.1")
    adapter = VmLifecycleAdapter(orch, lifecycle="multipass")
    config = VmConfig(name="my-vm", cpus=2, memory="4G", disk="20G")

    info = adapter.ensure_running(config)

    orch.ensure_running.assert_called_once()
    assert info.name == "my-vm"
    assert info.host == "192.168.1.1"
    assert info.user == "ubuntu"


def test_vm_lifecycle_adapter_destroy_calls_teardown() -> None:
    orch = _make_orchestrator()
    adapter = VmLifecycleAdapter(orch, lifecycle="multipass")
    info = VmInfo(name="my-vm", host="10.0.0.1", user="ubuntu", home="/home/ubuntu")

    adapter.destroy(info)

    orch.teardown.assert_called_once()


def test_multipass_vm_adapter_factory() -> None:
    orch = _make_orchestrator("10.0.0.2")
    adapter = MultipassVmAdapter(orch)
    config = VmConfig(name="vm1")

    info = adapter.ensure_running(config)
    assert info.name == "vm1"


def test_azure_vm_adapter_factory() -> None:
    orch = _make_orchestrator("4.5.6.7")
    adapter = AzureVmAdapter(orch)
    config = VmConfig(name="azure-vm")

    info = adapter.ensure_running(config)
    assert info.host == "4.5.6.7"


def test_proxmox_vm_adapter_factory() -> None:
    from pathlib import Path
    from workflow_tasks.vm.proxmox import ProxmoxVmProvider
    from workflow_tasks.vm.adapters import ProxmoxVmAdapter

    provider = ProxmoxVmProvider(repo_root=Path("/repo"))
    adapter = ProxmoxVmAdapter(provider)
    assert adapter._lifecycle == "proxmox"


def test_proxmox_vm_adapter_propagates_credentials_to_ensure_running() -> None:
    from workflow_tasks.vm.adapters import ProxmoxVmAdapter
    from workflow_tasks.vm.models import VmRequest

    orch = _make_orchestrator("10.0.0.5")
    credentials = VmRequest(
        lifecycle="proxmox",
        proxmox_host="192.168.1.100",
        proxmox_node="pve",
        proxmox_user="root@pam",
        proxmox_password="secret",
        proxmox_template_id=101,
    )
    adapter = ProxmoxVmAdapter(orch, credentials=credentials)
    config = VmConfig(name="nanofaas-proxmox", cpus=4, memory="8G", disk="30G")

    adapter.ensure_running(config)

    call_args = orch.ensure_running.call_args[0][0]
    assert call_args.proxmox_host == "192.168.1.100"
    assert call_args.proxmox_node == "pve"
    assert call_args.proxmox_password == "secret"
    assert call_args.proxmox_template_id == 101
    assert call_args.name == "nanofaas-proxmox"
    assert call_args.cpus == 4


def test_proxmox_vm_adapter_propagates_credentials_to_destroy() -> None:
    from workflow_tasks.vm.adapters import ProxmoxVmAdapter
    from workflow_tasks.vm.models import VmRequest

    orch = _make_orchestrator()
    credentials = VmRequest(
        lifecycle="proxmox",
        proxmox_host="192.168.1.100",
        proxmox_node="pve",
        proxmox_password="secret",
    )
    adapter = ProxmoxVmAdapter(orch, credentials=credentials)
    info = VmInfo(name="nanofaas-proxmox-loadgen", host="10.0.0.5", user="ubuntu", home="/home/ubuntu")

    adapter.destroy(info)

    call_args = orch.teardown.call_args[0][0]
    assert call_args.proxmox_host == "192.168.1.100"
    assert call_args.proxmox_password == "secret"
    assert call_args.name == "nanofaas-proxmox-loadgen"


def test_vm_lifecycle_adapter_without_credentials_uses_bare_request() -> None:
    orch = _make_orchestrator("10.0.0.1")
    adapter = VmLifecycleAdapter(orch, lifecycle="multipass")
    config = VmConfig(name="vm1", cpus=2, memory="4G", disk="20G")

    adapter.ensure_running(config)

    call_args = orch.ensure_running.call_args[0][0]
    assert call_args.lifecycle == "multipass"
    assert call_args.name == "vm1"
