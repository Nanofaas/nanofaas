from __future__ import annotations

from pathlib import Path

from workflow_tasks.shell import RecordingShell
from workflow_tasks.vm.models import VmRequest
from workflow_tasks.vm.orchestrator import VmOrchestrator


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
