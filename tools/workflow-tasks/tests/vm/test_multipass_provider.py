from __future__ import annotations

from pathlib import Path
from unittest.mock import MagicMock

from workflow_tasks.vm.models import VmRequest
from workflow_tasks.vm.multipass import MultipassVmProvider


def _make_provider(workspace_root: Path | None = None) -> tuple[MultipassVmProvider, MagicMock, MagicMock]:
    shell = MagicMock()
    shell.run.return_value = MagicMock(return_code=0, stdout="", stderr="", command=[])
    client = MagicMock()
    provider = MultipassVmProvider(
        workspace_root=workspace_root or Path("/repo"),
        shell=shell,
        multipass_client=client,
    )
    return provider, shell, client


def test_remote_home_default_ubuntu() -> None:
    provider, _, _ = _make_provider()
    req = VmRequest(lifecycle="multipass", name="vm1", user="ubuntu")
    assert provider.remote_home(req) == "/home/ubuntu"


def test_remote_home_root_user() -> None:
    provider, _, _ = _make_provider()
    req = VmRequest(lifecycle="multipass", name="vm1", user="root")
    assert provider.remote_home(req) == "/root"


def test_remote_home_custom() -> None:
    provider, _, _ = _make_provider()
    req = VmRequest(lifecycle="multipass", name="vm1", user="ubuntu", home="/custom")
    assert provider.remote_home(req) == "/custom"


def test_vm_name_uses_name_field() -> None:
    provider, _, _ = _make_provider()
    req = VmRequest(lifecycle="multipass", name="my-vm")
    assert provider.vm_name(req) == "my-vm"


def test_vm_name_default() -> None:
    provider, _, _ = _make_provider()
    req = VmRequest(lifecycle="multipass")
    assert provider.vm_name(req) == "nanofaas-e2e"


def test_teardown_dry_run_returns_ok() -> None:
    provider, shell, _ = _make_provider()
    req = VmRequest(lifecycle="multipass", name="my-vm")
    result = provider.teardown(req, dry_run=True)
    assert result.return_code == 0


def test_connection_host_external() -> None:
    provider, _, _ = _make_provider()
    req = VmRequest(lifecycle="external", host="192.168.1.100")
    assert provider.connection_host(req) == "192.168.1.100"


def test_ensure_running_external_calls_ssh() -> None:
    provider, shell, _ = _make_provider()
    req = VmRequest(lifecycle="external", host="192.168.1.100", user="ubuntu")
    provider.ensure_running(req)
    shell.run.assert_called_once()
    args = shell.run.call_args[0][0]
    assert "ssh" in args


def test_transfer_from_dry_run() -> None:
    provider, shell, _ = _make_provider()
    req = VmRequest(lifecycle="multipass", name="my-vm")
    result = provider.transfer_from(req, source="/remote/file.txt", destination=Path("/local/file.txt"), dry_run=True)
    assert result.return_code == 0
