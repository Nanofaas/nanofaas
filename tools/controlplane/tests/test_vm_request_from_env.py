"""
Tests for vm_request_from_env() - the shared factory extracted from cli_runtime and k3s_runtime
in the simplify pass (M10-M13).

Gate: vm_request_from_env must reconstruct a VmRequest from the canonical env vars set by
E2eRunner._vm_env() and must produce correct defaults when env vars are absent.
"""
from __future__ import annotations

from controlplane_tool.vm_models import VmRequest, vm_request_from_env


def test_vm_request_from_env_uses_defaults_when_env_absent() -> None:
    request = vm_request_from_env()
    assert request.lifecycle == "multipass"
    assert request.user == "ubuntu"
    assert request.cpus == 4
    assert request.memory == "8G"
    assert request.disk == "30G"
    assert request.name is None
    assert request.host is None
    assert request.home is None


def test_vm_request_from_env_reads_lifecycle(monkeypatch) -> None:
    monkeypatch.setenv("E2E_VM_LIFECYCLE", "external")
    monkeypatch.setenv("E2E_VM_HOST", "10.0.0.5")
    request = vm_request_from_env()
    assert request.lifecycle == "external"
    assert request.host == "10.0.0.5"


def test_vm_request_from_env_reads_vm_name(monkeypatch) -> None:
    monkeypatch.setenv("VM_NAME", "my-e2e-vm")
    request = vm_request_from_env()
    assert request.name == "my-e2e-vm"


def test_vm_request_from_env_reads_user(monkeypatch) -> None:
    monkeypatch.setenv("E2E_VM_USER", "ci-runner")
    request = vm_request_from_env()
    assert request.user == "ci-runner"


def test_vm_request_from_env_reads_cpus_and_resources(monkeypatch) -> None:
    monkeypatch.setenv("CPUS", "8")
    monkeypatch.setenv("MEMORY", "16G")
    monkeypatch.setenv("DISK", "60G")
    request = vm_request_from_env()
    assert request.cpus == 8
    assert request.memory == "16G"
    assert request.disk == "60G"


def test_vm_request_from_env_reads_home(monkeypatch) -> None:
    monkeypatch.setenv("E2E_VM_HOME", "/home/ubuntu")
    request = vm_request_from_env()
    assert request.home == "/home/ubuntu"


def test_vm_request_from_env_produces_valid_pydantic_model(monkeypatch) -> None:
    monkeypatch.setenv("E2E_VM_LIFECYCLE", "multipass")
    monkeypatch.setenv("VM_NAME", "e2e")
    result = vm_request_from_env()
    assert isinstance(result, VmRequest)
