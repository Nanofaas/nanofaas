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
