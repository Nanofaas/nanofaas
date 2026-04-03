from pathlib import Path

from controlplane_tool.shell_backend import RecordingShell, ScriptedShell
from controlplane_tool.vm_adapter import VmOrchestrator
from controlplane_tool.vm_models import VmRequest


def test_vm_up_multipass_plans_expected_backend_calls(tmp_path: Path) -> None:
    orchestrator = VmOrchestrator(repo_root=Path("/repo"), shell=RecordingShell())
    request = VmRequest(
        lifecycle="multipass",
        name="nanofaas-e2e",
        cpus=4,
        memory="8G",
        disk="30G",
    )

    orchestrator.ensure_running(request, dry_run=True)

    assert "multipass" in orchestrator.shell.commands[0]
    assert "launch" in orchestrator.shell.commands[0]


def test_vm_sync_external_plans_rsync_or_scp_via_backend() -> None:
    orchestrator = VmOrchestrator(repo_root=Path("/repo"), shell=RecordingShell())
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev", home="/srv/dev")

    orchestrator.sync_project(request, dry_run=True)

    assert "vm.example.test" in " ".join(orchestrator.shell.commands[0])
    assert any(tool in orchestrator.shell.commands[0] for tool in ("rsync", "scp"))


MULTIPASS_RUNNING_JSON = """{
  "info": {
    "nanofaas-e2e": {
      "state": "Running",
      "ipv4": ["192.168.64.10"]
    }
  }
}"""


def test_ensure_running_is_idempotent_for_existing_multipass_vm() -> None:
    shell = ScriptedShell(
        stdout_map={
            ("multipass", "info", "nanofaas-e2e", "--format", "json"): MULTIPASS_RUNNING_JSON,
        }
    )
    orchestrator = VmOrchestrator(repo_root=Path("/repo"), shell=shell)

    orchestrator.ensure_running(VmRequest(lifecycle="multipass", name="nanofaas-e2e"))

    assert not any(command[:3] == ["multipass", "launch", "--name"] for command in shell.commands)
