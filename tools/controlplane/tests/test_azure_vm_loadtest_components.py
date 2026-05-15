from __future__ import annotations

import subprocess
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from controlplane_tool.infra.vm.vm_models import VmRequest


def _azure_request(**kwargs) -> VmRequest:
    defaults = dict(
        lifecycle="azure",
        name="nanofaas-azure",
        user="azureuser",
        azure_resource_group="my-rg",
        azure_location="westeurope",
    )
    defaults.update(kwargs)
    return VmRequest(**defaults)


def _make_orchestrator(tmp_path: Path):
    from controlplane_tool.infra.vm.azure_vm_adapter import AzureVmOrchestrator
    return AzureVmOrchestrator(tmp_path)


# ----------------------------------------------------------------- remote_home

def test_remote_home_uses_request_home_when_set(tmp_path):
    orch = _make_orchestrator(tmp_path)
    request = _azure_request(home="/custom/home")
    assert orch.remote_home(request) == "/custom/home"


def test_remote_home_defaults_to_home_slash_user(tmp_path):
    orch = _make_orchestrator(tmp_path)
    request = _azure_request(user="azureuser")
    assert orch.remote_home(request) == "/home/azureuser"


def test_remote_home_returns_root_for_root_user(tmp_path):
    orch = _make_orchestrator(tmp_path)
    request = _azure_request(user="root")
    assert orch.remote_home(request) == "/root"


def test_remote_project_dir_appends_nanofaas(tmp_path):
    orch = _make_orchestrator(tmp_path)
    request = _azure_request(user="azureuser")
    assert orch.remote_project_dir(request) == "/home/azureuser/nanofaas"


# ----------------------------------------------------------------- teardown

def test_teardown_calls_vm_delete(tmp_path, monkeypatch):
    mock_vm = MagicMock()
    mock_client = MagicMock()
    mock_client.get_vm.return_value = mock_vm
    monkeypatch.setattr(
        "controlplane_tool.infra.vm.azure_vm_adapter.AzureClient",
        lambda **kwargs: mock_client,
    )

    orch = _make_orchestrator(tmp_path)
    result = orch.teardown(_azure_request())

    mock_vm.delete.assert_called_once()
    assert result.return_code == 0


def test_teardown_silences_vm_not_found(tmp_path, monkeypatch):
    from azure_vm.exceptions import VmNotFoundError

    mock_client = MagicMock()
    mock_client.get_vm.side_effect = VmNotFoundError("nanofaas-azure")
    monkeypatch.setattr(
        "controlplane_tool.infra.vm.azure_vm_adapter.AzureClient",
        lambda **kwargs: mock_client,
    )

    orch = _make_orchestrator(tmp_path)
    result = orch.teardown(_azure_request())

    assert result.return_code == 0


# ----------------------------------------------------------------- ensure_running

def test_ensure_running_calls_client_ensure_running(tmp_path, monkeypatch):
    mock_client = MagicMock()
    monkeypatch.setattr(
        "controlplane_tool.infra.vm.azure_vm_adapter.AzureClient",
        lambda **kwargs: mock_client,
    )

    orch = _make_orchestrator(tmp_path)
    result = orch.ensure_running(_azure_request(azure_vm_size="Standard_B4ms"))

    mock_client.ensure_running.assert_called_once_with(
        "nanofaas-azure",
        vm_size="Standard_B4ms",
        image_urn=None,
        ssh_key_path=None,
    )
    assert result.return_code == 0


def test_ensure_running_passes_azure_fields(tmp_path, monkeypatch):
    mock_client = MagicMock()
    monkeypatch.setattr(
        "controlplane_tool.infra.vm.azure_vm_adapter.AzureClient",
        lambda **kwargs: mock_client,
    )

    orch = _make_orchestrator(tmp_path)
    orch.ensure_running(_azure_request(
        name="custom-vm",
        azure_vm_size="Standard_D2s_v3",
        azure_image_urn="Canonical:0001-com-ubuntu-server-noble:24_04-lts:latest",
        azure_ssh_key_path="/home/user/.ssh/id_rsa",
    ))

    mock_client.ensure_running.assert_called_once_with(
        "custom-vm",
        vm_size="Standard_D2s_v3",
        image_urn="Canonical:0001-com-ubuntu-server-noble:24_04-lts:latest",
        ssh_key_path="/home/user/.ssh/id_rsa",
    )


# ----------------------------------------------------------------- exec_argv

def test_exec_argv_calls_exec_structured_and_maps_result(tmp_path, monkeypatch):
    from azure_vm._backend import CommandResult as AzureResult

    mock_vm = MagicMock()
    mock_vm.exec_structured.return_value = AzureResult(
        args=[], returncode=0, stdout="hello", stderr=""
    )
    mock_client = MagicMock()
    mock_client.get_vm.return_value = mock_vm
    monkeypatch.setattr(
        "controlplane_tool.infra.vm.azure_vm_adapter.AzureClient",
        lambda **kwargs: mock_client,
    )

    orch = _make_orchestrator(tmp_path)
    result = orch.exec_argv(_azure_request(), ("echo", "hello"), cwd="/home/azureuser")

    mock_vm.exec_structured.assert_called_once_with(
        ["echo", "hello"], env=None, cwd="/home/azureuser"
    )
    assert result.return_code == 0
    assert result.stdout == "hello"


def test_exec_argv_passes_env(tmp_path, monkeypatch):
    from azure_vm._backend import CommandResult as AzureResult

    mock_vm = MagicMock()
    mock_vm.exec_structured.return_value = AzureResult(
        args=[], returncode=0, stdout="", stderr=""
    )
    mock_client = MagicMock()
    mock_client.get_vm.return_value = mock_vm
    monkeypatch.setattr(
        "controlplane_tool.infra.vm.azure_vm_adapter.AzureClient",
        lambda **kwargs: mock_client,
    )

    orch = _make_orchestrator(tmp_path)
    orch.exec_argv(_azure_request(), ("k6", "run"), env={"NANOFAAS_URL": "http://1.2.3.4:30080"})

    mock_vm.exec_structured.assert_called_once_with(
        ["k6", "run"], env={"NANOFAAS_URL": "http://1.2.3.4:30080"}, cwd=None
    )


# ----------------------------------------------------------------- transfer_to

def test_transfer_to_calls_vm_transfer(tmp_path, monkeypatch):
    mock_vm = MagicMock()
    mock_client = MagicMock()
    mock_client.get_vm.return_value = mock_vm
    monkeypatch.setattr(
        "controlplane_tool.infra.vm.azure_vm_adapter.AzureClient",
        lambda **kwargs: mock_client,
    )

    source = tmp_path / "script.js"
    source.write_text("// k6 script")
    orch = _make_orchestrator(tmp_path)
    result = orch.transfer_to(_azure_request(), source=source, destination="/home/azureuser/script.js")

    mock_vm.transfer.assert_called_once_with(str(source), "/home/azureuser/script.js")
    assert result.return_code == 0


# ----------------------------------------------------------------- transfer_from

def test_transfer_from_uses_scp_subprocess(tmp_path, monkeypatch):
    mock_vm = MagicMock()
    mock_vm.wait_for_ip.return_value = "1.2.3.4"
    mock_client = MagicMock()
    mock_client.get_vm.return_value = mock_vm
    monkeypatch.setattr(
        "controlplane_tool.infra.vm.azure_vm_adapter.AzureClient",
        lambda **kwargs: mock_client,
    )

    scp_calls: list[list[str]] = []

    def fake_run(cmd, **kwargs):
        scp_calls.append(list(cmd))
        return subprocess.CompletedProcess(cmd, 0, stdout="", stderr="")

    monkeypatch.setattr("controlplane_tool.infra.vm.azure_vm_adapter.subprocess.run", fake_run)

    dest = tmp_path / "k6-summary.json"
    orch = _make_orchestrator(tmp_path)
    result = orch.transfer_from(
        _azure_request(user="azureuser"),
        source="/home/azureuser/results/k6-summary.json",
        destination=dest,
    )

    assert len(scp_calls) == 1
    cmd = scp_calls[0]
    assert cmd[0] == "scp"
    assert "azureuser@1.2.3.4:/home/azureuser/results/k6-summary.json" in cmd
    assert str(dest) in cmd
    assert result.return_code == 0
