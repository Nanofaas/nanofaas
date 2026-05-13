import json
from pathlib import Path

import pytest
from multipass import FakeBackend, MultipassClient
from multipass._backend import CommandResult

from controlplane_tool.core.shell_backend import RecordingShell
from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator, repo_rsync_command
from controlplane_tool.infra.vm.vm_models import VmRequest


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
    assert "--delete-excluded" in orchestrator.shell.commands[0]
    assert "--exclude=.venv/" in orchestrator.shell.commands[0]
    assert "--exclude=node_modules/" in orchestrator.shell.commands[0]


def test_vm_sync_multipass_plans_rsync_with_generated_artifact_excludes() -> None:
    orchestrator = VmOrchestrator(repo_root=Path("/repo"), shell=RecordingShell())
    request = VmRequest(lifecycle="multipass", name="nanofaas-e2e")

    result = orchestrator.sync_project(request, dry_run=True)

    assert result.command[:4] == ["rsync", "-az", "--delete", "--delete-excluded"]
    assert "--exclude=.venv/" in result.command
    assert "--exclude=node_modules/" in result.command
    assert "--exclude=.git/" in result.command
    assert "ubuntu@<multipass-ip:nanofaas-e2e>:/home/ubuntu/nanofaas/" in result.command[-1]


def test_vm_sync_does_not_exclude_source_packages_named_building() -> None:
    command = repo_rsync_command(
        source=Path("/repo"),
        user="ubuntu",
        host="vm.example.test",
        destination="/home/ubuntu/nanofaas",
    )

    assert "--exclude=building/" not in command
    assert "--exclude=/building/" in command


def test_ensure_running_is_idempotent_for_existing_multipass_vm() -> None:
    name = "nanofaas-e2e"
    backend = FakeBackend({
        ("multipass", "info", name, "--format", "json"): _info_result(name, state="Running", ipv4=["192.168.64.10"]),
    })
    backend.set_default(_ok())
    orchestrator = VmOrchestrator(repo_root=Path("/repo"), multipass_client=MultipassClient(backend=backend))

    result = orchestrator.ensure_running(VmRequest(lifecycle="multipass", name=name))

    assert result.return_code == 0
    assert not any("launch" in " ".join(call) for call in backend.calls)
    assert any(call[:4] == ["multipass", "exec", name, "--"] for call in backend.calls)


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
    backend.set_default(_ok())
    orchestrator = VmOrchestrator(repo_root=Path("/repo"), multipass_client=MultipassClient(backend=backend))
    orchestrator._ssh_public_key = "ssh-ed25519 AAAA test@example"

    result = orchestrator.ensure_running(VmRequest(lifecycle="multipass", name=name))

    assert result.return_code == 0
    assert any(call == ["multipass", "start", name] for call in backend.calls)
    assert any(
        call[:4] == ["multipass", "exec", name, "--"]
        and "/home/ubuntu/.ssh/authorized_keys" in call[-1]
        for call in backend.calls
    )


def test_remote_exec_for_multipass_routes_through_shell_backend() -> None:
    shell = RecordingShell()
    orchestrator = VmOrchestrator(repo_root=Path("/repo"), shell=shell)

    orchestrator.remote_exec(
        VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        command="echo hello",
        dry_run=False,
    )

    assert shell.commands == [["multipass", "exec", "nanofaas-e2e", "--", "bash", "-lc", "echo hello"]]


def test_vm_adapter_exposes_registry_container_and_k3s_registry_as_separate_operations() -> None:
    orchestrator = VmOrchestrator(repo_root=Path("/repo"), shell=RecordingShell())

    assert orchestrator.ensure_registry_container is not None
    assert orchestrator.configure_k3s_registry is not None


def test_vm_orchestrator_matches_ansible_private_key_to_multipass_public_key(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    ssh_dir = tmp_path / ".ssh"
    ssh_dir.mkdir()
    ed25519_private = ssh_dir / "id_ed25519"
    ed25519_public = ssh_dir / "id_ed25519.pub"
    rsa_private = ssh_dir / "id_rsa"
    rsa_public = ssh_dir / "id_rsa.pub"
    ed25519_private.write_text("ed25519-private", encoding="utf-8")
    ed25519_public.write_text("ssh-ed25519 AAAA first@example\n", encoding="utf-8")
    rsa_private.write_text("rsa-private", encoding="utf-8")
    rsa_public.write_text("ssh-rsa BBBB second@example\n", encoding="utf-8")

    monkeypatch.setattr("controlplane_tool.infra.vm.vm_adapter.Path.home", lambda: tmp_path)
    monkeypatch.setattr(
        "controlplane_tool.infra.vm.vm_adapter.find_ssh_public_key",
        lambda: "ssh-rsa BBBB second@example",
    )

    orchestrator = VmOrchestrator(repo_root=Path("/repo"), shell=RecordingShell())

    assert orchestrator.ansible.private_key_path == rsa_private


def test_vm_orchestrator_sets_private_key_none_when_no_on_disk_match(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Public key injected into the VM but private key lives in SSH agent (not on disk).

    When find_ssh_public_key() returns a key that has no matching on-disk private key,
    _private_key_path must be None so Ansible omits --private-key and falls back to the
    SSH agent, which holds the actual private key.  The old fallback returned the first
    complete key pair on disk, mismatching what was authorized in the VM.
    """
    ssh_dir = tmp_path / ".ssh"
    ssh_dir.mkdir()
    # Only the public key is present (hardware/agent key — private part is not on disk)
    agent_pub = ssh_dir / "id_ed25519.pub"
    agent_pub.write_text("ssh-ed25519 AAAA agent@example\n", encoding="utf-8")
    # A complete rsa pair also exists (but must NOT be chosen as the Ansible key)
    rsa_private = ssh_dir / "id_rsa"
    rsa_public = ssh_dir / "id_rsa.pub"
    rsa_private.write_text("rsa-private", encoding="utf-8")
    rsa_public.write_text("ssh-rsa BBBB other@example\n", encoding="utf-8")

    monkeypatch.setattr("controlplane_tool.infra.vm.vm_adapter.Path.home", lambda: tmp_path)
    monkeypatch.setattr(
        "controlplane_tool.infra.vm.vm_adapter.find_ssh_public_key",
        lambda: "ssh-ed25519 AAAA agent@example",
    )

    orchestrator = VmOrchestrator(repo_root=Path("/repo"), shell=RecordingShell())

    # The public key that will be injected into the VM
    assert orchestrator._ssh_public_key == "ssh-ed25519 AAAA agent@example"
    # No on-disk private key matches → None so Ansible uses the SSH agent
    assert orchestrator.ansible.private_key_path is None


def test_exec_argv_multipass_builds_shell_command_and_routes_via_shell_backend() -> None:
    shell = RecordingShell()
    orchestrator = VmOrchestrator(repo_root=Path("/repo"), shell=shell)
    name = "nanofaas-e2e"

    orchestrator.exec_argv(
        VmRequest(lifecycle="multipass", name=name),
        ["docker", "building", "-t", "myimage", "."],
        env={"DOCKER_BUILDKIT": "1"},
        cwd="/srv/project",
    )

    assert len(shell.commands) == 1
    cmd = shell.commands[0]
    # Routes through shell backend as multipass exec <name> -- bash -lc <script>
    assert cmd[:4] == ["multipass", "exec", name, "--"]
    shell_cmd = cmd[-1]
    assert "/srv/project" in shell_cmd
    assert "DOCKER_BUILDKIT" in shell_cmd
    assert "docker building -t myimage ." in shell_cmd


def test_exec_argv_external_vm_builds_shell_string_and_runs_via_ssh() -> None:
    shell = RecordingShell()
    orchestrator = VmOrchestrator(repo_root=Path("/repo"), shell=shell)

    orchestrator.exec_argv(
        VmRequest(lifecycle="external", host="vm.example.test", user="dev"),
        ["echo", "hello world"],
        cwd="/tmp",
        dry_run=False,
    )

    assert len(shell.commands) == 1
    cmd = shell.commands[0]
    assert cmd[0] == "ssh"
    assert "dev@vm.example.test" in cmd
    shell_str = cmd[-1]
    assert "cd /tmp" in shell_str
    assert "echo 'hello world'" in shell_str


def test_exec_argv_multipass_dry_run_returns_placeholder_command() -> None:
    orchestrator = VmOrchestrator(repo_root=Path("/repo"))
    request = VmRequest(lifecycle="multipass", name="nanofaas-e2e")

    result = orchestrator.exec_argv(request, ["make", "building"], dry_run=True)

    assert result.return_code == 0
    assert "multipass" in result.command
    assert "exec" in result.command


def test_transfer_to_multipass_routes_via_multipass_transfer() -> None:
    shell = RecordingShell()
    orchestrator = VmOrchestrator(repo_root=Path("/repo"), shell=shell)
    request = VmRequest(lifecycle="multipass", name="nanofaas-e2e-loadgen")

    orchestrator.transfer_to(request, source=Path("/tmp/script.js"), destination="/home/ubuntu/k6/script.js")

    assert shell.commands == [
        ["multipass", "transfer", "/tmp/script.js", "nanofaas-e2e-loadgen:/home/ubuntu/k6/script.js"]
    ]


def test_transfer_from_multipass_routes_via_multipass_transfer() -> None:
    shell = RecordingShell()
    orchestrator = VmOrchestrator(repo_root=Path("/repo"), shell=shell)
    request = VmRequest(lifecycle="multipass", name="nanofaas-e2e-loadgen")

    orchestrator.transfer_from(request, source="/home/ubuntu/k6/k6-summary.json", destination=Path("/tmp/k6-summary.json"))

    assert shell.commands == [
        ["multipass", "transfer", "nanofaas-e2e-loadgen:/home/ubuntu/k6/k6-summary.json", "/tmp/k6-summary.json"]
    ]


def test_ensure_running_repairs_authorized_keys_for_existing_multipass_vm() -> None:
    name = "nanofaas-e2e"
    public_key = "ssh-ed25519 AAAA test@example"
    backend = FakeBackend({
        ("multipass", "info", name, "--format", "json"): _info_result(name, state="Running", ipv4=["192.168.64.10"]),
    })
    backend.set_default(_ok())
    orchestrator = VmOrchestrator(repo_root=Path("/repo"), multipass_client=MultipassClient(backend=backend))
    orchestrator._ssh_public_key = public_key

    result = orchestrator.ensure_running(VmRequest(lifecycle="multipass", name=name))

    assert result.return_code == 0
    assert any(
        call[:4] == ["multipass", "exec", name, "--"]
        and "/home/ubuntu/.ssh/authorized_keys" in call[-1]
        for call in backend.calls
    )
