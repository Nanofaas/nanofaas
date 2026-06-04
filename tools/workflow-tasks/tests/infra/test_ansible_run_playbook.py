from pathlib import Path

from workflow_tasks.infra.ansible import AnsibleAdapter
from workflow_tasks.shell import RecordingShell
from workflow_tasks.vm.models import VmRequest


def _external_request() -> VmRequest:
    return VmRequest(lifecycle="external", host="10.0.0.5", user="ubuntu")


def test_run_playbook_builds_ansible_command_and_runs_on_host() -> None:
    shell = RecordingShell()
    adapter = AnsibleAdapter(
        repo_root=Path("/repo"),
        shell=shell,
        host_resolver=lambda request, dry_run=False: "10.0.0.5",
        private_key_path=Path("/keys/id_ed25519"),
    )

    adapter.run_playbook(
        "install-k6.yml",
        _external_request(),
        extra_vars={"ansible_port": "2222"},
    )

    assert len(shell.commands) == 1
    command = shell.commands[0]
    assert command[0] == "ansible-playbook"
    assert "-i" in command and "10.0.0.5," in command
    assert "-u" in command and "ubuntu" in command
    assert "--private-key" in command and "/keys/id_ed25519" in command
    assert "-e" in command and "ansible_port=2222" in command
    assert command[-1].endswith("playbooks/install-k6.yml")


def test_install_k6_uses_install_k6_playbook() -> None:
    shell = RecordingShell()
    adapter = AnsibleAdapter(
        repo_root=Path("/repo"),
        shell=shell,
        host_resolver=lambda request, dry_run=False: "10.0.0.5",
    )

    adapter.install_k6(_external_request())

    assert shell.commands[0][-1].endswith("playbooks/install-k6.yml")
