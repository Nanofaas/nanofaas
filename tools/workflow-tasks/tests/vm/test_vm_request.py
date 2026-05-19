from __future__ import annotations
import pytest
from workflow_tasks.vm.models import VmConfig, VmInfo, VmLifecycle, VmRequest

def test_vm_request_minimal() -> None:
    req = VmRequest(lifecycle="multipass", name="my-vm")
    assert req.lifecycle == "multipass"
    assert req.name == "my-vm"
    assert req.user == "ubuntu"
    assert req.cpus == 4
    assert req.memory == "12G"
    assert req.disk == "30G"

def test_vm_request_external_requires_host() -> None:
    with pytest.raises(Exception):
        VmRequest(lifecycle="external")

def test_vm_lifecycle_values() -> None:
    assert "multipass" in VmLifecycle.__args__  # type: ignore[attr-defined]
    assert "azure" in VmLifecycle.__args__  # type: ignore[attr-defined]
    assert "external" in VmLifecycle.__args__  # type: ignore[attr-defined]

def test_vm_config_still_works() -> None:
    cfg = VmConfig(name="test", cpus=2)
    assert cfg.name == "test"
    assert cfg.cpus == 2

def test_vm_info_still_works() -> None:
    info = VmInfo(name="test", host="10.0.0.1", user="ubuntu", home="/home/ubuntu")
    assert info.host == "10.0.0.1"
