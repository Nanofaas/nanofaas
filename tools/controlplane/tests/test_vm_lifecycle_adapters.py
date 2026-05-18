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
