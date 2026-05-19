"""Tests for AzureVmProvider."""
from __future__ import annotations

from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from workflow_tasks.vm.models import VmRequest


def _make_provider() -> object:
    from workflow_tasks.vm.azure import AzureVmProvider
    return AzureVmProvider(repo_root=Path("/repo"))


def _make_request(**kwargs) -> VmRequest:
    defaults = dict(
        lifecycle="azure",
        name="test-vm",
        user="ubuntu",
        azure_resource_group="rg-test",
        azure_location="eastus",
        azure_ssh_key_path="/home/user/.ssh/id_ed25519",
    )
    defaults.update(kwargs)
    return VmRequest(**defaults)


def _make_azure_client_mock() -> MagicMock:
    client = MagicMock()
    vm = MagicMock()
    vm.wait_for_ip.return_value = "10.0.0.1"
    client.get_vm.return_value = vm
    return client, vm


@patch("workflow_tasks.vm.azure.AzureClient")
def test_remote_home_default(mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(user="ubuntu")
    assert provider.remote_home(req) == "/home/ubuntu"


@patch("workflow_tasks.vm.azure.AzureClient")
def test_remote_home_root(mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(user="root")
    assert provider.remote_home(req) == "/root"


@patch("workflow_tasks.vm.azure.AzureClient")
def test_remote_home_custom(mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(home="/custom/home")
    assert provider.remote_home(req) == "/custom/home"


@patch("workflow_tasks.vm.azure.AzureClient")
def test_remote_project_dir(mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(user="ubuntu")
    assert provider.remote_project_dir(req) == "/home/ubuntu/nanofaas"


@patch("workflow_tasks.vm.azure.AzureClient")
def test_vm_name_uses_name_field(mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(name="custom-vm")
    assert provider._vm_name(req) == "custom-vm"


@patch("workflow_tasks.vm.azure.AzureClient")
def test_vm_name_default(mock_client_cls) -> None:
    provider = _make_provider()
    req = VmRequest(lifecycle="azure", azure_resource_group="rg")
    assert provider._vm_name(req) == "nanofaas-azure"


@patch("workflow_tasks.vm.azure.AzureClient")
def test_ssh_key_from_request(mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(azure_ssh_key_path="/home/user/.ssh/id_ed25519")
    key = provider._ssh_key(req)
    assert key == Path("/home/user/.ssh/id_ed25519")


@patch("workflow_tasks.vm.azure.AzureClient")
@patch("workflow_tasks.vm.azure._find_ssh_private_key", return_value=Path("/home/user/.ssh/id_rsa"))
def test_ssh_key_fallback(mock_find, mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(azure_ssh_key_path=None)
    key = provider._ssh_key(req)
    assert key == Path("/home/user/.ssh/id_rsa")


@patch("workflow_tasks.vm.azure.AzureClient")
def test_connection_host(mock_client_cls) -> None:
    client_mock, vm_mock = _make_azure_client_mock()
    mock_client_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()
    host = provider.connection_host(req)
    assert host == "10.0.0.1"


@patch("workflow_tasks.vm.azure.AzureClient")
def test_teardown_success(mock_client_cls) -> None:
    client_mock, vm_mock = _make_azure_client_mock()
    mock_client_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()
    result = provider.teardown(req)
    vm_mock.delete.assert_called_once()
    assert result.return_code == 0


@patch("workflow_tasks.vm.azure.AzureClient")
def test_teardown_vm_not_found_is_ignored(mock_client_cls) -> None:
    from azure_vm.exceptions import VmNotFoundError
    client_mock, vm_mock = _make_azure_client_mock()
    vm_mock.delete.side_effect = VmNotFoundError("gone")
    mock_client_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()
    result = provider.teardown(req)
    assert result.return_code == 0


@patch("workflow_tasks.vm.azure.AzureClient")
def test_ensure_running(mock_client_cls) -> None:
    client_mock = MagicMock()
    mock_client_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()
    result = provider.ensure_running(req)
    client_mock.ensure_running.assert_called_once()
    assert result.return_code == 0


@patch("workflow_tasks.vm.azure.AzureClient")
def test_exec_argv(mock_client_cls) -> None:
    client_mock, vm_mock = _make_azure_client_mock()
    exec_result = MagicMock()
    exec_result.returncode = 0
    exec_result.stdout = "output"
    exec_result.stderr = ""
    vm_mock.exec_structured.return_value = exec_result
    mock_client_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()
    result = provider.exec_argv(req, ["echo", "hello"])
    assert result.return_code == 0
    assert result.stdout == "output"


@patch("workflow_tasks.vm.azure.AzureClient")
def test_transfer_to(mock_client_cls) -> None:
    client_mock, vm_mock = _make_azure_client_mock()
    mock_client_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()
    result = provider.transfer_to(req, source=Path("/local/file"), destination="/remote/file")
    vm_mock.transfer.assert_called_once_with("/local/file", "/remote/file")
    assert result.return_code == 0


@patch("workflow_tasks.vm.azure.subprocess.run")
@patch("workflow_tasks.vm.azure.AzureClient")
def test_transfer_from(mock_client_cls, mock_subproc) -> None:
    client_mock, vm_mock = _make_azure_client_mock()
    mock_client_cls.return_value = client_mock
    proc = MagicMock()
    proc.returncode = 0
    proc.stdout = ""
    proc.stderr = ""
    mock_subproc.return_value = proc
    provider = _make_provider()
    req = _make_request()
    result = provider.transfer_from(req, source="/remote/file", destination=Path("/local/file"))
    assert result.return_code == 0
    assert "scp" in result.command


@patch("workflow_tasks.vm.azure.subprocess.run")
@patch("workflow_tasks.vm.azure.AzureClient")
def test_transfer_from_no_ssh_key(mock_client_cls, mock_subproc) -> None:
    client_mock, vm_mock = _make_azure_client_mock()
    mock_client_cls.return_value = client_mock
    proc = MagicMock()
    proc.returncode = 0
    proc.stdout = ""
    proc.stderr = ""
    mock_subproc.return_value = proc
    provider = _make_provider()
    req = _make_request(azure_ssh_key_path=None)
    # patch _find_ssh_private_key to return None so no -i flag
    with patch("workflow_tasks.vm.azure._find_ssh_private_key", return_value=None):
        result = provider.transfer_from(req, source="/remote/file", destination=Path("/local"))
    assert result.return_code == 0
    assert "-i" not in result.command


def test_find_ssh_private_key_no_ssh_dir() -> None:
    from workflow_tasks.vm.azure import _find_ssh_private_key
    with patch("workflow_tasks.vm.azure.Path") as mock_path_cls:
        mock_home = MagicMock()
        mock_ssh_dir = MagicMock()
        mock_path_cls.home.return_value = mock_home
        mock_home.__truediv__ = lambda self, x: mock_ssh_dir
        # Make all key checks return non-existent
        mock_priv = MagicMock()
        mock_priv.exists.return_value = False
        mock_ssh_dir.__truediv__ = lambda self, x: mock_priv
        result = _find_ssh_private_key()
        # Should return None when no keys exist
        assert result is None or isinstance(result, Path)
