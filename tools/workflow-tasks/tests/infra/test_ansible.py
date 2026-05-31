from __future__ import annotations

from pathlib import Path

from workflow_tasks.infra.ansible import AnsibleAdapter
from workflow_tasks.shell import RecordingShell, ShellBackend, ShellExecutionResult
from workflow_tasks.vm.models import VmRequest


def test_provision_base_uses_ops_ansible_root() -> None:
    shell = RecordingShell()
    adapter = AnsibleAdapter(repo_root=Path("/repo"), shell=shell)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")

    adapter.provision_base(request, dry_run=True)

    command = shell.commands[0]
    assert "ansible-playbook" in command
    assert "ops/ansible/playbooks/provision-base.yml" in " ".join(command)
    assert "vm.example.test," in command


def test_configure_k3s_registry_sets_expected_extra_vars() -> None:
    shell = RecordingShell()
    adapter = AnsibleAdapter(repo_root=Path("/repo"), shell=shell)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")

    adapter.configure_k3s_registry(request, registry="registry.example.test:5000", dry_run=True)

    rendered = " ".join(shell.commands[0])
    assert "configure-k3s-registry.yml" in rendered
    assert "registry=registry.example.test:5000" in rendered
    assert "registry_port=5000" in rendered


def test_provision_k3s_sets_expected_extra_vars() -> None:
    shell = RecordingShell()
    adapter = AnsibleAdapter(repo_root=Path("/repo"), shell=shell)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")

    adapter.provision_k3s(request, kubeconfig_path="/home/dev/.kube/config", dry_run=True)

    rendered = " ".join(shell.commands[0])
    assert "provision-k3s.yml" in rendered
    assert "kubeconfig_path=/home/dev/.kube/config" in rendered


def test_ensure_registry_container_sets_expected_extra_vars() -> None:
    shell = RecordingShell()
    adapter = AnsibleAdapter(repo_root=Path("/repo"), shell=shell)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")

    adapter.ensure_registry_container(request, registry="reg.test:5000", dry_run=True)

    rendered = " ".join(shell.commands[0])
    assert "ensure-registry.yml" in rendered
    assert "registry_container_name=nanofaas-e2e-registry" in rendered
    assert "registry_host=reg.test" in rendered


def test_configure_registry_runs_both_playbooks() -> None:
    shell = RecordingShell()
    adapter = AnsibleAdapter(repo_root=Path("/repo"), shell=shell)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")

    adapter.configure_registry(request, registry="reg.test:5000", dry_run=True)

    assert len(shell.commands) == 2
    assert "ensure-registry.yml" in " ".join(shell.commands[0])
    assert "configure-k3s-registry.yml" in " ".join(shell.commands[1])


def test_configure_registry_short_circuits_on_ensure_failure() -> None:
    """When ensure_registry_container returns non-zero, configure_registry returns
    early without running configure-k3s-registry.yml."""

    class FailingEnsureShell(ShellBackend):
        def __init__(self) -> None:
            self.commands: list[list[str]] = []

        def run(
            self,
            command: list[str],
            *,
            cwd: Path | None = None,
            env: dict[str, str] | None = None,
            dry_run: bool = False,
        ) -> ShellExecutionResult:
            self.commands.append(command)
            rc = 1 if "ensure-registry.yml" in " ".join(command) else 0
            return ShellExecutionResult(command=command, return_code=rc, dry_run=dry_run, env=env or {})

    shell = FailingEnsureShell()
    adapter = AnsibleAdapter(repo_root=Path("/repo"), shell=shell)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")

    result = adapter.configure_registry(request, registry="reg.test:5000", dry_run=True)

    # Only the ensure step was run; configure-k3s-registry.yml was NOT invoked.
    assert len(shell.commands) == 1
    assert "ensure-registry.yml" in " ".join(shell.commands[0])
    assert result.return_code != 0
