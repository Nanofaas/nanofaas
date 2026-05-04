from __future__ import annotations

from pathlib import Path

from controlplane_tool.core.shell_backend import RecordingShell
from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.infra.vm.vm_tasks import provision_base_task


def test_provision_base_task_delegates_to_vm_orchestrator() -> None:
    orchestrator = VmOrchestrator(repo_root=Path("/repo"), shell=RecordingShell())
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")

    result = provision_base_task(
        orchestrator=orchestrator,
        request=request,
        install_helm=True,
        helm_version="3.16.4",
        dry_run=True,
    )

    assert "ansible-playbook" in result.command
    assert "provision-base.yml" in " ".join(result.command)
