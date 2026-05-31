from __future__ import annotations

from pathlib import Path
from unittest.mock import MagicMock

import pytest

from multipass import MultipassCommandError
from workflow_tasks.shell import RecordingShell, ShellExecutionResult
from workflow_tasks.vm.models import VmRequest
from workflow_tasks.vm.orchestrator import VmOrchestrator


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _make_orch(
    repo_root: Path = Path("/repo"),
    shell: object | None = None,
    ansible: object | None = None,
    multipass_client: object | None = None,
) -> VmOrchestrator:
    """Build a VmOrchestrator with controllable dependencies."""
    if shell is None:
        shell = RecordingShell()
    mock_ansible = ansible if ansible is not None else MagicMock()
    mock_client = multipass_client if multipass_client is not None else MagicMock()
    orch = VmOrchestrator(
        repo_root=repo_root,
        shell=shell,
        ansible=mock_ansible,
        multipass_client=mock_client,
    )
    return orch


def _ok_result(command: list[str] | None = None) -> ShellExecutionResult:
    return ShellExecutionResult(command=command or ["x"], return_code=0)


def _err_result(command: list[str] | None = None) -> ShellExecutionResult:
    return ShellExecutionResult(command=command or ["x"], return_code=1)


# ---------------------------------------------------------------------------
# Existing tests (unchanged)
# ---------------------------------------------------------------------------

def test_remote_project_dir_uses_nanofaas_suffix() -> None:
    orch = VmOrchestrator(repo_root=Path("/repo"), shell=RecordingShell())
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")
    assert orch.remote_project_dir(request).endswith("/nanofaas")


def test_install_dependencies_delegates_to_ansible_provision_base() -> None:
    shell = RecordingShell()
    orch = VmOrchestrator(repo_root=Path("/repo"), shell=shell)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")

    orch.install_dependencies(request, dry_run=True)

    rendered = " ".join(shell.commands[-1])
    assert "ops/ansible/playbooks/provision-base.yml" in rendered


def test_remote_path_for_local_uses_repo_root_as_default_root() -> None:
    orch = VmOrchestrator(repo_root=Path("/repo"), shell=RecordingShell())
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")
    remote = orch.remote_path_for_local(request, Path("/repo/control-plane/app.jar"))
    assert remote.endswith("/nanofaas/control-plane/app.jar")


# ---------------------------------------------------------------------------
# New tests
# ---------------------------------------------------------------------------

def test_kubeconfig_path_ends_with_kube_config() -> None:
    orch = _make_orch()
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")
    path = orch.kubeconfig_path(request)
    assert path.endswith("/.kube/config")


def test_remote_path_for_local_outside_root_with_fallback_subdir() -> None:
    orch = _make_orch(repo_root=Path("/repo"))
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")
    # /tmp/script.sh is outside /repo
    remote = orch.remote_path_for_local(
        request,
        Path("/tmp/script.sh"),
        fallback_subdir="extras",
    )
    assert remote.endswith("/extras/script.sh")


def test_remote_path_for_local_outside_root_no_fallback_subdir() -> None:
    orch = _make_orch(repo_root=Path("/repo"))
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")
    remote = orch.remote_path_for_local(
        request,
        Path("/tmp/something.txt"),
    )
    # No fallback_subdir → just filename appended to remote_project_dir
    assert remote.endswith("/something.txt")
    assert "extras" not in remote


def test_sync_project_external_records_rsync_to_host() -> None:
    shell = RecordingShell()
    orch = _make_orch(shell=shell)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")

    orch.sync_project(request, dry_run=True)

    assert len(shell.commands) >= 1
    rendered = " ".join(shell.commands[-1])
    assert "rsync" in rendered
    assert "vm.example.test" in rendered


def test_sync_project_multipass_records_rsync_with_ssh_rsh() -> None:
    shell = RecordingShell()
    orch = _make_orch(shell=shell)
    request = VmRequest(lifecycle="multipass", name="nanofaas-e2e")

    result = orch.sync_project(request, dry_run=True)

    assert "rsync" in result.command
    # ssh_rsh is injected via -e flag
    assert "-e" in result.command


def test_install_k3s_delegates_to_ansible_provision_k3s() -> None:
    mock_ansible = MagicMock()
    mock_ansible.provision_k3s.return_value = _ok_result()
    orch = _make_orch(ansible=mock_ansible)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")

    orch.install_k3s(request, dry_run=True)

    mock_ansible.provision_k3s.assert_called_once()
    call_kwargs = mock_ansible.provision_k3s.call_args
    # kubeconfig_path is passed as a keyword argument
    kube_path = call_kwargs.kwargs.get("kubeconfig_path") or call_kwargs[1].get("kubeconfig_path")
    assert kube_path is not None
    assert kube_path.endswith("/.kube/config")


def test_setup_registry_short_circuits_when_ensure_registry_fails() -> None:
    mock_ansible = MagicMock()
    mock_ansible.ensure_registry_container.return_value = _err_result()
    orch = _make_orch(ansible=mock_ansible)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")

    result = orch.setup_registry(request, dry_run=True)

    assert result.return_code != 0
    mock_ansible.configure_k3s_registry.assert_not_called()


def test_setup_registry_calls_configure_when_ensure_succeeds() -> None:
    mock_ansible = MagicMock()
    mock_ansible.ensure_registry_container.return_value = _ok_result()
    mock_ansible.configure_k3s_registry.return_value = _ok_result()
    orch = _make_orch(ansible=mock_ansible)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")

    result = orch.setup_registry(request, dry_run=True)

    mock_ansible.configure_k3s_registry.assert_called_once()
    assert result.return_code == 0


def test_ensure_registry_container_delegates_to_ansible() -> None:
    mock_ansible = MagicMock()
    mock_ansible.ensure_registry_container.return_value = _ok_result()
    orch = _make_orch(ansible=mock_ansible)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")

    orch.ensure_registry_container(request, dry_run=True)

    mock_ansible.ensure_registry_container.assert_called_once()


def test_configure_k3s_registry_delegates_to_ansible() -> None:
    mock_ansible = MagicMock()
    mock_ansible.configure_k3s_registry.return_value = _ok_result()
    orch = _make_orch(ansible=mock_ansible)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")

    orch.configure_k3s_registry(request, dry_run=True)

    mock_ansible.configure_k3s_registry.assert_called_once()


def test_export_kubeconfig_external_dry_run_records_scp_command() -> None:
    shell = RecordingShell()
    orch = _make_orch(shell=shell)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")

    result = orch.export_kubeconfig(request, destination=Path("/tmp/kube/config"), dry_run=True)

    assert "scp" in result.command


def test_export_kubeconfig_multipass_dry_run_records_transfer_command() -> None:
    shell = RecordingShell()
    orch = _make_orch(shell=shell)
    request = VmRequest(lifecycle="multipass", name="nanofaas-e2e")

    result = orch.export_kubeconfig(request, destination=Path("/tmp/kube/config"), dry_run=True)

    rendered = " ".join(result.command)
    assert "multipass" in rendered or "transfer" in rendered


def test_export_kubeconfig_multipass_live_success_returns_zero() -> None:
    shell = RecordingShell()
    mock_client = MagicMock()
    # configure client.get_vm(...).transfer(...) to succeed silently
    mock_vm = MagicMock()
    mock_vm.transfer.return_value = None
    mock_client.get_vm.return_value = mock_vm

    orch = _make_orch(shell=shell, multipass_client=mock_client)
    request = VmRequest(lifecycle="multipass", name="nanofaas-e2e")

    result = orch.export_kubeconfig(request, destination=Path("/tmp/kube/config"), dry_run=False)

    assert result.return_code == 0


def test_export_kubeconfig_multipass_propagates_multipass_command_error() -> None:
    shell = RecordingShell()
    mock_client = MagicMock()
    mock_vm = MagicMock()
    error = MultipassCommandError(
        args=["multipass", "transfer"],
        returncode=1,
        stdout="",
        stderr="transfer failed",
    )
    mock_vm.transfer.side_effect = error
    mock_client.get_vm.return_value = mock_vm

    orch = _make_orch(shell=shell, multipass_client=mock_client)
    request = VmRequest(lifecycle="multipass", name="nanofaas-e2e")

    result = orch.export_kubeconfig(request, destination=Path("/tmp/kube/config"), dry_run=False)

    assert result.return_code != 0
