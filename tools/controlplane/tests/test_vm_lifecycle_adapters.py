from __future__ import annotations

from unittest.mock import MagicMock

from workflow_tasks.vm.models import VmConfig, VmInfo

from controlplane_tool.infra.vm_lifecycle_adapters import MultipassVmAdapter


def _make_mock_vm(connection_host: str = "10.0.0.1") -> MagicMock:
    vm = MagicMock()
    vm.connection_host.return_value = connection_host
    return vm


def test_multipass_ensure_running_calls_vm_ensure_and_returns_info() -> None:
    vm = _make_mock_vm("192.168.64.5")
    adapter = MultipassVmAdapter(vm)
    config = VmConfig(name="my-vm", cpus=2, memory="4G", disk="20G")

    info = adapter.ensure_running(config)

    vm.ensure_running.assert_called_once()
    assert info.name == "my-vm"
    assert info.host == "192.168.64.5"
    assert info.user == "ubuntu"


def test_multipass_destroy_calls_vm_teardown() -> None:
    vm = _make_mock_vm()
    adapter = MultipassVmAdapter(vm)
    info = VmInfo(name="my-vm", host="10.0.0.1", user="ubuntu", home="/home/ubuntu")

    adapter.destroy(info)

    vm.teardown.assert_called_once()


from controlplane_tool.infra.vm_lifecycle_adapters import AzureVmAdapter


def test_azure_ensure_running_returns_vm_info() -> None:
    azure_vm = MagicMock()
    azure_vm.connection_host.return_value = "4.5.6.7"
    adapter = AzureVmAdapter(azure_vm)
    config = VmConfig(name="azure-loadgen", cpus=2, memory="8G", disk="30G")

    info = adapter.ensure_running(config)

    azure_vm.ensure_running.assert_called_once()
    assert info.host == "4.5.6.7"
    assert info.name == "azure-loadgen"


def test_azure_destroy_calls_vm_teardown() -> None:
    azure_vm = MagicMock()
    adapter = AzureVmAdapter(azure_vm)
    info = VmInfo(name="azure-loadgen", host="4.5.6.7", user="ubuntu", home="/home/ubuntu")

    adapter.destroy(info)

    azure_vm.teardown.assert_called_once()
