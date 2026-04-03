from pathlib import Path

from controlplane_tool.ansible_adapter import AnsibleAdapter
from controlplane_tool.shell_backend import RecordingShell, ScriptedShell
from controlplane_tool.vm_models import VmRequest


def test_provision_base_uses_ops_ansible_root() -> None:
    shell = RecordingShell()
    adapter = AnsibleAdapter(repo_root=Path("/repo"), shell=shell)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")

    adapter.provision_base(request, dry_run=True)

    command = shell.commands[0]
    assert "ansible-playbook" in command
    assert "ops/ansible/playbooks/provision-base.yml" in " ".join(command)
    assert "vm.example.test," in command


def test_configure_registry_sets_expected_extra_vars() -> None:
    shell = RecordingShell()
    adapter = AnsibleAdapter(repo_root=Path("/repo"), shell=shell)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")

    adapter.configure_registry(request, registry="registry.example.test:5000", dry_run=True)

    rendered = " ".join(shell.commands[0])
    assert "configure-registry.yml" in rendered
    assert "registry=registry.example.test:5000" in rendered
    assert "registry_host=registry.example.test" in rendered


MULTIPASS_INFO_JSON = """{
  "info": {
    "nanofaas-e2e": {
      "ipv4": ["192.168.64.10"],
      "state": "Running"
    }
  }
}"""


def test_provision_base_for_multipass_targets_vm_ip() -> None:
    shell = ScriptedShell(
        stdout_map={
            ("multipass", "info", "nanofaas-e2e", "--format", "json"): MULTIPASS_INFO_JSON,
        }
    )
    adapter = AnsibleAdapter(repo_root=Path("/repo"), shell=shell)
    request = VmRequest(lifecycle="multipass", name="nanofaas-e2e", user="ubuntu")

    adapter.provision_base(request, dry_run=False)

    rendered = " ".join(shell.commands[-1])
    assert "-i" in shell.commands[-1]
    assert "192.168.64.10," in rendered
    assert "localhost," not in rendered
