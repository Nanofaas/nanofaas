from pathlib import Path

import pytest

from workflow_tasks.infra.ansible import AnsibleAdapter, RunPlaybook
from workflow_tasks.shell import RecordingShell, ShellBackend, ShellExecutionResult
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


class _FailingShell(ShellBackend):
    """Minimal shell that always fails — pattern mirrors tests/infra/test_ansible.py."""

    def run(self, command, *, cwd=None, env=None, dry_run=False) -> ShellExecutionResult:
        return ShellExecutionResult(command=command, return_code=2, stderr="boom")


def test_run_playbook_task_runs_playbook_and_returns_none() -> None:
    shell = RecordingShell()
    adapter = AnsibleAdapter(
        repo_root=Path("/repo"),
        shell=shell,
        host_resolver=lambda request, dry_run=False: "10.0.0.5",
    )
    task = RunPlaybook(
        task_id="loadgen.install_k6",
        title="Install k6 on loadgen VM",
        adapter=adapter,
        playbook="install-k6.yml",
        request=_external_request(),
    )

    assert task.run() is None
    assert shell.commands[0][-1].endswith("playbooks/install-k6.yml")


def test_run_playbook_task_raises_on_nonzero_exit() -> None:
    adapter = AnsibleAdapter(
        repo_root=Path("/repo"),
        shell=_FailingShell(),
        host_resolver=lambda request, dry_run=False: "10.0.0.5",
    )
    task = RunPlaybook(
        task_id="loadgen.install_k6",
        title="Install k6",
        adapter=adapter,
        playbook="install-k6.yml",
        request=_external_request(),
    )

    with pytest.raises(RuntimeError, match="boom"):
        task.run()


from workflow_tasks.infra.ansible import install_k6_task


def test_install_k6_task_factory_builds_runplaybook_with_connectivity() -> None:
    shell = RecordingShell()
    task = install_k6_task(
        task_id="loadgen.install_k6",
        title="Install k6 on loadgen VM",
        repo_root=Path("/repo"),
        shell=shell,
        host="10.0.0.5",
        user="ubuntu",
        private_key=Path("/keys/id_ed25519"),
        port=2222,
    )

    assert isinstance(task, RunPlaybook)
    assert task.task_id == "loadgen.install_k6"
    assert task.playbook == "install-k6.yml"

    task.run()
    command = shell.commands[0]
    assert "10.0.0.5," in command
    assert "ubuntu" in command
    assert "/keys/id_ed25519" in command
    assert "ansible_port=2222" in command


def test_install_k6_task_factory_omits_port_when_none() -> None:
    shell = RecordingShell()
    task = install_k6_task(
        task_id="loadgen.install_k6",
        title="Install k6",
        repo_root=Path("/repo"),
        shell=shell,
        host="10.0.0.5",
        user="ubuntu",
    )
    task.run()
    assert not any("ansible_port=" in arg for arg in shell.commands[0])


class _AnsibleLikeFailingShell(ShellBackend):
    """Mimics ansible: real failure on stdout, benign WARNING on stderr."""

    def run(self, command, *, cwd=None, env=None, dry_run=False) -> ShellExecutionResult:
        return ShellExecutionResult(
            command=command,
            return_code=2,
            stdout="fatal: [10.0.0.5]: FAILED! => No package matching 'k6' is available",
            stderr="[WARNING]: Module remote_tmp /root/.ansible/tmp did not exist",
        )


def test_run_playbook_error_surfaces_stdout_failure_not_just_stderr_warning() -> None:
    adapter = AnsibleAdapter(
        repo_root=Path("/repo"),
        shell=_AnsibleLikeFailingShell(),
        host_resolver=lambda request, dry_run=False: "10.0.0.5",
    )
    task = RunPlaybook(
        task_id="loadgen.install_k6",
        title="Install k6",
        adapter=adapter,
        playbook="install-k6.yml",
        request=_external_request(),
    )

    with pytest.raises(RuntimeError) as exc:
        task.run()

    message = str(exc.value)
    # The real ansible failure (on stdout) must be surfaced, not masked by the stderr warning.
    assert "No package matching 'k6'" in message
