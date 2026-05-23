"""Tests for ProxmoxVmProvider."""
from __future__ import annotations

from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from workflow_tasks.vm.models import VmRequest


def _make_provider() -> object:
    from workflow_tasks.vm.proxmox import ProxmoxVmProvider
    return ProxmoxVmProvider(repo_root=Path("/repo"))


def _make_request(**kwargs) -> VmRequest:
    defaults = dict(
        lifecycle="proxmox",
        name="test-vm",
        user="ubuntu",
        proxmox_host="pve.example.com",
        proxmox_node="pve",
        proxmox_user="root@pam",
        proxmox_password="secret",
        proxmox_template_id=100,
        proxmox_ssh_key_path="/home/user/.ssh/id_ed25519",
    )
    defaults.update(kwargs)
    return VmRequest(**defaults)


def _make_proxmox_client_mock() -> tuple:
    client = MagicMock()
    vm = MagicMock()
    vm.wait_for_ip.return_value = "192.168.1.100"
    client.get_vm.return_value = vm
    return client, vm


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_remote_home_default(mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(user="ubuntu")
    assert provider.remote_home(req) == "/home/ubuntu"


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_remote_home_root(mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(user="root")
    assert provider.remote_home(req) == "/root"


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_remote_home_custom(mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(home="/custom/home")
    assert provider.remote_home(req) == "/custom/home"


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_remote_project_dir(mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(user="ubuntu")
    assert provider.remote_project_dir(req) == "/home/ubuntu/nanofaas"


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_vm_name_uses_name_field(mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(name="custom-vm")
    assert provider._vm_name(req) == "custom-vm"


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_vm_name_default(mock_client_cls) -> None:
    provider = _make_provider()
    req = VmRequest(lifecycle="proxmox", proxmox_password="secret")
    assert provider._vm_name(req) == "nanofaas-proxmox"


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_ssh_key_from_request(mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(proxmox_ssh_key_path="/home/user/.ssh/id_ed25519")
    key = provider._ssh_key(req)
    assert key == Path("/home/user/.ssh/id_ed25519")


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
@patch("workflow_tasks.vm.proxmox._find_ssh_private_key_path", return_value=Path("/home/user/.ssh/id_rsa"))
def test_ssh_key_fallback(mock_find, mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(proxmox_ssh_key_path=None)
    key = provider._ssh_key(req)
    assert key == Path("/home/user/.ssh/id_rsa")


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_connection_host(mock_client_cls) -> None:
    client_mock, vm_mock = _make_proxmox_client_mock()
    mock_client_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()
    host = provider.connection_host(req)
    assert host == "192.168.1.100"
    client_mock.get_vm.assert_called_once_with("test-vm")
    vm_mock.wait_for_ip.assert_called_once()


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_teardown_success(mock_client_cls) -> None:
    client_mock, vm_mock = _make_proxmox_client_mock()
    mock_client_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()
    result = provider.teardown(req)
    vm_mock.delete.assert_called_once()
    assert result.return_code == 0


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_teardown_vm_not_found_is_ignored(mock_client_cls) -> None:
    from proxmox_sdk.exceptions import VmNotFoundError
    client_mock, vm_mock = _make_proxmox_client_mock()
    vm_mock.delete.side_effect = VmNotFoundError("gone")
    mock_client_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()
    result = provider.teardown(req)
    assert result.return_code == 0


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_ensure_running(mock_client_cls) -> None:
    client_mock = MagicMock()
    mock_client_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request(cpus=2, memory="4G", disk="20G")
    result = provider.ensure_running(req)
    client_mock.ensure_running.assert_called_once_with(
        "test-vm",
        template_id=100,
        node="pve",
        cores=2,
        memory_mb=4096,
        disk_gb=20,
    )
    assert result.return_code == 0


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_exec_argv(mock_client_cls) -> None:
    client_mock, vm_mock = _make_proxmox_client_mock()
    exec_result = MagicMock()
    exec_result.exit_code = 0
    exec_result.stdout = "output"
    exec_result.stderr = ""
    vm_mock.exec_structured.return_value = exec_result
    mock_client_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()
    result = provider.exec_argv(req, ["echo", "hello"])
    assert result.return_code == 0
    assert result.stdout == "output"
    vm_mock.exec_structured.assert_called_once_with(["echo", "hello"], env=None, cwd=None)


@patch("workflow_tasks.vm.proxmox.subprocess.run")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_transfer_to(mock_client_cls, mock_subproc) -> None:
    client_mock, vm_mock = _make_proxmox_client_mock()
    mock_client_cls.return_value = client_mock
    proc = MagicMock()
    proc.returncode = 0
    proc.stdout = ""
    proc.stderr = ""
    mock_subproc.return_value = proc
    provider = _make_provider()
    req = _make_request()
    result = provider.transfer_to(req, source=Path("/local/file"), destination="/remote/file")
    assert result.return_code == 0
    assert "scp" in result.command
    cmd = result.command
    assert "/local/file" in cmd
    assert "ubuntu@192.168.1.100:/remote/file" in cmd


@patch("workflow_tasks.vm.proxmox.subprocess.run")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_transfer_from(mock_client_cls, mock_subproc) -> None:
    client_mock, vm_mock = _make_proxmox_client_mock()
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
    cmd = result.command
    assert "ubuntu@192.168.1.100:/remote/file" in cmd
    assert "/local/file" in cmd


@patch("workflow_tasks.vm.proxmox.subprocess.run")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_transfer_from_no_ssh_key(mock_client_cls, mock_subproc) -> None:
    client_mock, vm_mock = _make_proxmox_client_mock()
    mock_client_cls.return_value = client_mock
    proc = MagicMock()
    proc.returncode = 0
    proc.stdout = ""
    proc.stderr = ""
    mock_subproc.return_value = proc
    provider = _make_provider()
    req = _make_request(proxmox_ssh_key_path=None)
    with patch("workflow_tasks.vm.proxmox._find_ssh_private_key_path", return_value=None):
        result = provider.transfer_from(req, source="/remote/file", destination=Path("/local"))
    assert result.return_code == 0
    assert "-i" not in result.command
