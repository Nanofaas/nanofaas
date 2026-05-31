"""Extended tests for MultipassVmProvider and module-level helpers."""
from __future__ import annotations

from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from workflow_tasks.vm.models import VmRequest
from workflow_tasks.vm.multipass import (
    MultipassVmProvider,
    _sdk_error,
    repo_rsync_command,
    repo_sync_ssh_rsh,
    resolve_connection_host,
)


def _make_provider(
    workspace_root: Path | None = None,
    ssh_public_key: str | None = None,
    private_key_path: Path | None = None,
) -> tuple[MultipassVmProvider, MagicMock, MagicMock]:
    shell = MagicMock()
    shell.run.return_value = MagicMock(return_code=0, stdout="", stderr="", command=[])
    client = MagicMock()
    provider = MultipassVmProvider(
        workspace_root=workspace_root or Path("/repo"),
        shell=shell,
        multipass_client=client,
    )
    # Override internal state so tests don't depend on real ~/.ssh files
    provider._ssh_public_key = ssh_public_key
    provider._private_key_path = private_key_path
    return provider, shell, client


# ---------------------------------------------------------------------------
# Module-level helpers
# ---------------------------------------------------------------------------

def test_sdk_error_propagates_fields() -> None:
    err = MagicMock()
    err.args_list = ["multipass", "launch"]
    err.returncode = 1
    err.stdout = "out"
    err.stderr = "err"
    result = _sdk_error(err)
    assert result.return_code == 1
    assert result.stdout == "out"
    assert result.stderr == "err"
    assert result.command == ["multipass", "launch"]


def test_repo_sync_ssh_rsh_no_key() -> None:
    rsh = repo_sync_ssh_rsh(private_key_path=None)
    assert "ssh" in rsh
    assert "StrictHostKeyChecking=no" in rsh
    assert "-i" not in rsh


def test_repo_sync_ssh_rsh_with_key() -> None:
    rsh = repo_sync_ssh_rsh(private_key_path=Path("/home/user/.ssh/id_ed25519"))
    assert "-i" in rsh
    assert "id_ed25519" in rsh


def test_repo_sync_ssh_rsh_with_port_and_key() -> None:
    key = Path("/home/user/.ssh/id_ed25519")
    rsh = repo_sync_ssh_rsh(key, port=20001)
    assert "-p 20001" in rsh
    assert f"-i {key}" in rsh


def test_repo_rsync_command_no_ssh_rsh() -> None:
    cmd = repo_rsync_command(
        source=Path("/local/repo"),
        user="ubuntu",
        host="192.168.1.1",
        destination="/home/ubuntu/project",
    )
    assert "rsync" in cmd
    assert any("ubuntu@192.168.1.1:" in part for part in cmd)
    assert "-e" not in cmd


def test_repo_rsync_command_with_ssh_rsh() -> None:
    cmd = repo_rsync_command(
        source=Path("/local/repo"),
        user="ubuntu",
        host="192.168.1.1",
        destination="/home/ubuntu/project",
        ssh_rsh="ssh -o StrictHostKeyChecking=no",
    )
    assert "-e" in cmd
    assert "ssh -o StrictHostKeyChecking=no" in cmd


def test_resolve_connection_host_external() -> None:
    client = MagicMock()
    req = VmRequest(lifecycle="external", host="10.0.0.1")
    assert resolve_connection_host(req, client) == "10.0.0.1"


def test_resolve_connection_host_external_no_host_raises() -> None:
    client = MagicMock()
    req = VmRequest(lifecycle="external", host="10.0.0.1")
    # Manually clear host to test validation branch
    req_dict = req.model_dump()
    # Use a mock to simulate missing host
    with pytest.raises(Exception):
        resolve_connection_host(
            VmRequest.model_validate({**req_dict, "lifecycle": "external", "host": None}),
            client,
        )


def test_resolve_connection_host_dry_run() -> None:
    client = MagicMock()
    req = VmRequest(lifecycle="multipass", name="test-vm")
    result = resolve_connection_host(req, client, dry_run=True)
    assert "test-vm" in result
    assert "multipass-ip" in result


def test_resolve_connection_host_with_ipv4() -> None:
    client = MagicMock()
    info = MagicMock()
    info.ipv4 = ["192.168.64.10"]
    client.get_vm.return_value.info.return_value = info
    req = VmRequest(lifecycle="multipass", name="test-vm")
    assert resolve_connection_host(req, client) == "192.168.64.10"


def test_resolve_connection_host_vm_not_found_raises() -> None:
    from multipass import VmNotFoundError
    client = MagicMock()
    client.get_vm.return_value.info.side_effect = VmNotFoundError("not found")
    req = VmRequest(lifecycle="multipass", name="missing-vm")
    with pytest.raises(RuntimeError, match="missing-vm"):
        resolve_connection_host(req, client)


def test_resolve_connection_host_no_ipv4_raises() -> None:
    client = MagicMock()
    info = MagicMock()
    info.ipv4 = []
    client.get_vm.return_value.info.return_value = info
    req = VmRequest(lifecycle="multipass", name="no-ip-vm")
    with pytest.raises(RuntimeError, match="no-ip-vm"):
        resolve_connection_host(req, client)


# ---------------------------------------------------------------------------
# MultipassVmProvider methods
# ---------------------------------------------------------------------------

def test_resolve_multipass_ipv4_dry_run() -> None:
    provider, _, _ = _make_provider()
    req = VmRequest(lifecycle="multipass", name="test-vm")
    result = provider.resolve_multipass_ipv4(req, dry_run=True)
    assert "test-vm" in result


def test_ensure_running_multipass_dry_run() -> None:
    provider, shell, _ = _make_provider()
    req = VmRequest(lifecycle="multipass", name="my-vm")
    result = provider.ensure_running(req, dry_run=True)
    assert result.return_code == 0


def test_ensure_running_multipass_calls_client() -> None:
    provider, shell, client = _make_provider()
    req = VmRequest(lifecycle="multipass", name="my-vm")
    # ensure_running with ssh_public_key=None so _ensure_multipass_authorized_key is a no-op
    provider._ssh_public_key = None
    provider.ensure_running(req, dry_run=False)
    client.ensure_running.assert_called_once()


def test_ensure_running_with_ssh_key_calls_authorize() -> None:
    provider, shell, client = _make_provider(ssh_public_key="ssh-ed25519 AAAA test@host")
    req = VmRequest(lifecycle="multipass", name="my-vm")
    provider.ensure_running(req, dry_run=False)
    client.ensure_running.assert_called_once()
    # authorized key should be injected
    client.get_vm.return_value.exec.assert_called()


def test_ensure_running_with_ssh_key_root_user() -> None:
    provider, shell, client = _make_provider(ssh_public_key="ssh-ed25519 AAAA test@host")
    req = VmRequest(lifecycle="multipass", name="my-vm", user="root")
    provider.ensure_running(req, dry_run=False)
    client.ensure_running.assert_called_once()
    client.get_vm.return_value.exec.assert_called()


def test_teardown_external_lifecycle() -> None:
    provider, shell, _ = _make_provider()
    req = VmRequest(lifecycle="external", host="10.0.0.1")
    provider.teardown(req, dry_run=False)
    shell.run.assert_called_once()
    args = shell.run.call_args[0][0]
    assert "echo" in args or "Skipping" in str(args)


def test_teardown_multipass_calls_delete() -> None:
    provider, shell, client = _make_provider()
    req = VmRequest(lifecycle="multipass", name="my-vm")
    result = provider.teardown(req, dry_run=False)
    client.get_vm.return_value.delete.assert_called_once()
    assert result.return_code == 0


def test_teardown_multipass_command_error() -> None:
    from multipass import MultipassCommandError
    provider, shell, client = _make_provider()
    err = MultipassCommandError(["multipass", "delete", "my-vm"], returncode=1, stdout="", stderr="error")
    client.get_vm.return_value.delete.side_effect = err
    req = VmRequest(lifecycle="multipass", name="my-vm")
    result = provider.teardown(req, dry_run=False)
    assert result.return_code == 1


def test_teardown_multipass_vm_not_found() -> None:
    from multipass import VmNotFoundError
    provider, shell, client = _make_provider()
    client.get_vm.return_value.delete.side_effect = VmNotFoundError("gone")
    req = VmRequest(lifecycle="multipass", name="gone-vm")
    result = provider.teardown(req, dry_run=False)
    # VmNotFoundError is swallowed, returns ok
    assert result.return_code == 0


def test_inspect_external() -> None:
    provider, shell, _ = _make_provider()
    req = VmRequest(lifecycle="external", host="10.0.0.1")
    provider.inspect(req)
    shell.run.assert_called_once()


def test_inspect_dry_run() -> None:
    provider, shell, _ = _make_provider()
    req = VmRequest(lifecycle="multipass", name="my-vm")
    result = provider.inspect(req, dry_run=True)
    assert result.return_code == 0


def test_inspect_multipass_success() -> None:
    provider, shell, client = _make_provider()
    info = MagicMock()
    info.name = "my-vm"
    info.state.value = "Running"
    info.ipv4 = ["192.168.64.5"]
    client.get_vm.return_value.info.return_value = info
    req = VmRequest(lifecycle="multipass", name="my-vm")
    result = provider.inspect(req)
    assert result.return_code == 0
    assert "my-vm" in result.stdout


def test_inspect_multipass_command_error() -> None:
    from multipass import MultipassCommandError
    provider, shell, client = _make_provider()
    err = MultipassCommandError(["multipass", "info", "my-vm"], returncode=1, stdout="", stderr="error")
    client.get_vm.return_value.info.side_effect = err
    req = VmRequest(lifecycle="multipass", name="my-vm")
    result = provider.inspect(req)
    assert result.return_code == 1


def test_exec_argv_multipass() -> None:
    provider, shell, client = _make_provider()
    shell.run.return_value = MagicMock(return_code=0, stdout="ok", stderr="", command=[])
    req = VmRequest(lifecycle="multipass", name="my-vm")
    result = provider.exec_argv(req, ["echo", "hello"], env={"K": "V"}, cwd="/home")
    shell.run.assert_called_once()


def test_exec_argv_with_env_and_cwd() -> None:
    provider, shell, client = _make_provider()
    shell.run.return_value = MagicMock(return_code=0, stdout="", stderr="", command=[])
    req = VmRequest(lifecycle="multipass", name="my-vm")
    provider.exec_argv(req, ("ls", "-la"), env={"PATH": "/usr/bin"}, cwd="/tmp")
    call_args = shell.run.call_args[0][0]
    assert "bash" in call_args


def test_remote_exec_external() -> None:
    provider, shell, _ = _make_provider()
    req = VmRequest(lifecycle="external", host="10.0.0.1", user="ubuntu")
    provider.remote_exec(req, command="echo hi")
    shell.run.assert_called_once()
    cmd = shell.run.call_args[0][0]
    assert "ssh" in cmd


def test_remote_exec_multipass_dry_run() -> None:
    provider, shell, _ = _make_provider()
    req = VmRequest(lifecycle="multipass", name="my-vm")
    result = provider.remote_exec(req, command="echo hi", dry_run=True)
    assert result.return_code == 0


def test_remote_exec_multipass_live() -> None:
    provider, shell, _ = _make_provider()
    req = VmRequest(lifecycle="multipass", name="my-vm")
    provider.remote_exec(req, command="echo hi")
    shell.run.assert_called_once()
    cmd = shell.run.call_args[0][0]
    assert "multipass" in cmd


def test_transfer_to_external() -> None:
    provider, shell, _ = _make_provider()
    req = VmRequest(lifecycle="external", host="10.0.0.1", user="ubuntu")
    provider.transfer_to(req, source=Path("/local/file"), destination="/remote/file")
    shell.run.assert_called_once()
    cmd = shell.run.call_args[0][0]
    assert "scp" in cmd


def test_transfer_to_multipass() -> None:
    provider, shell, _ = _make_provider()
    req = VmRequest(lifecycle="multipass", name="my-vm")
    provider.transfer_to(req, source=Path("/local/file"), destination="/remote/file")
    shell.run.assert_called_once()
    cmd = shell.run.call_args[0][0]
    assert "multipass" in cmd


def test_transfer_from_external() -> None:
    provider, shell, _ = _make_provider()
    req = VmRequest(lifecycle="external", host="10.0.0.1", user="ubuntu")
    provider.transfer_from(req, source="/remote/file", destination=Path("/local/file"))
    shell.run.assert_called_once()
    cmd = shell.run.call_args[0][0]
    assert "scp" in cmd
