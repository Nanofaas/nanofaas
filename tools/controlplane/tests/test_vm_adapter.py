import json
from pathlib import Path

from multipass import FakeBackend, MultipassClient
from multipass._backend import CommandResult

from controlplane_tool.shell_backend import RecordingShell
from controlplane_tool.vm_adapter import VmOrchestrator
from controlplane_tool.vm_models import VmRequest


def _running_info(name: str) -> str:
    return json.dumps({
        "info": {
            name: {
                "state": "Running",
                "ipv4": ["192.168.64.10"],
                "image_release": "22.04",
                "image_hash": "",
                "cpu_count": 4,
                "memory": {},
                "disks": {},
                "mounts": {},
            }
        }
    })


def _info_result(name: str, *, state: str = "Running", ipv4: list[str] | None = None) -> CommandResult:
    payload = {
        "info": {
            name: {
                "state": state,
                "ipv4": ipv4 or [],
                "image_release": "",
                "image_hash": "",
                "cpu_count": 4,
                "memory": {},
                "disks": {},
                "mounts": {},
            }
        }
    }
    return CommandResult(args=[], returncode=0, stdout=json.dumps(payload), stderr="")


def _ok() -> CommandResult:
    return CommandResult(args=[], returncode=0, stdout="", stderr="")


def test_vm_up_multipass_dry_run_returns_launch_command() -> None:
    orchestrator = VmOrchestrator(repo_root=Path("/repo"))
    request = VmRequest(lifecycle="multipass", name="nanofaas-e2e", cpus=4, memory="8G", disk="30G")

    result = orchestrator.ensure_running(request, dry_run=True)

    assert "multipass" in result.command
    assert "launch" in result.command


def test_vm_sync_external_plans_rsync_via_shell() -> None:
    orchestrator = VmOrchestrator(repo_root=Path("/repo"), shell=RecordingShell())
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev", home="/srv/dev")

    orchestrator.sync_project(request, dry_run=True)

    assert "vm.example.test" in " ".join(orchestrator.shell.commands[0])
    assert any(tool in orchestrator.shell.commands[0] for tool in ("rsync", "scp"))


def test_ensure_running_is_idempotent_for_existing_multipass_vm() -> None:
    name = "nanofaas-e2e"
    backend = FakeBackend({
        ("multipass", "info", name, "--format", "json"): _info_result(name, state="Running", ipv4=["192.168.64.10"]),
    })
    orchestrator = VmOrchestrator(repo_root=Path("/repo"), multipass_client=MultipassClient(backend=backend))

    result = orchestrator.ensure_running(VmRequest(lifecycle="multipass", name=name))

    assert result.return_code == 0
    assert not any("launch" in " ".join(call) for call in backend.calls)


def test_ensure_running_launches_when_vm_not_found() -> None:
    name = "nanofaas-e2e"
    backend = FakeBackend()
    # info → not found
    backend.push("multipass", "info", name, "--format", "json",
                 result=CommandResult(args=[], returncode=1, stdout="",
                                      stderr=f"instance '{name}' does not exist"))
    # launch → success (set_default covers any remaining calls)
    backend.set_default(_ok())

    orchestrator = VmOrchestrator(repo_root=Path("/repo"), multipass_client=MultipassClient(backend=backend))
    result = orchestrator.ensure_running(VmRequest(lifecycle="multipass", name=name))

    assert result.return_code == 0
    assert any("launch" in " ".join(call) for call in backend.calls)


def test_ensure_running_purges_and_relaunches_deleted_vm() -> None:
    name = "nanofaas-e2e"
    backend = FakeBackend()
    backend.push("multipass", "info", name, "--format", "json",
                 result=_info_result(name, state="Deleted"))
    backend.set_default(_ok())

    orchestrator = VmOrchestrator(repo_root=Path("/repo"), multipass_client=MultipassClient(backend=backend))
    result = orchestrator.ensure_running(VmRequest(lifecycle="multipass", name=name))

    assert result.return_code == 0
    assert any(call == ["multipass", "purge"] for call in backend.calls)
    assert any("launch" in " ".join(call) for call in backend.calls)


def test_ensure_running_starts_stopped_vm() -> None:
    name = "nanofaas-e2e"
    backend = FakeBackend({
        ("multipass", "info", name, "--format", "json"): _info_result(name, state="Stopped"),
        ("multipass", "start", name): _ok(),
    })
    orchestrator = VmOrchestrator(repo_root=Path("/repo"), multipass_client=MultipassClient(backend=backend))

    result = orchestrator.ensure_running(VmRequest(lifecycle="multipass", name=name))

    assert result.return_code == 0
    assert any(call == ["multipass", "start", name] for call in backend.calls)


def test_remote_exec_for_multipass_routes_through_shell_backend() -> None:
    shell = RecordingShell()
    orchestrator = VmOrchestrator(repo_root=Path("/repo"), shell=shell)

    orchestrator.remote_exec(
        VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        command="echo hello",
        dry_run=False,
    )

    assert shell.commands == [["multipass", "exec", "nanofaas-e2e", "--", "bash", "-lc", "echo hello"]]
