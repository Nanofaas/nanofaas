from pathlib import Path

from controlplane_tool.infra.vm.ansible_adapter import AnsibleAdapter
from controlplane_tool.core.shell_backend import RecordingShell
from controlplane_tool.infra.vm.vm_models import VmRequest


def test_provision_base_uses_ops_ansible_root() -> None:
    shell = RecordingShell()
    adapter = AnsibleAdapter(repo_root=Path("/repo"), shell=shell)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")

    adapter.provision_base(request, dry_run=True)

    command = shell.commands[0]
    assert "ansible-playbook" in command
    assert "ops/ansible/playbooks/provision-base.yml" in " ".join(command)
    assert "vm.example.test," in command


def test_ensure_registry_container_sets_expected_extra_vars() -> None:
    shell = RecordingShell()
    adapter = AnsibleAdapter(repo_root=Path("/repo"), shell=shell)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")

    adapter.ensure_registry_container(
        request,
        registry="registry.example.test:5000",
        dry_run=True,
    )

    rendered = " ".join(shell.commands[0])
    assert "ensure-registry.yml" in rendered
    assert "registry_host=registry.example.test" in rendered


def test_configure_k3s_registry_sets_expected_extra_vars() -> None:
    shell = RecordingShell()
    adapter = AnsibleAdapter(repo_root=Path("/repo"), shell=shell)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")

    adapter.configure_k3s_registry(
        request,
        registry="registry.example.test:5000",
        dry_run=True,
    )

    rendered = " ".join(shell.commands[0])
    assert "configure-k3s-registry.yml" in rendered
    assert "registry=registry.example.test:5000" in rendered
    assert "registry_port=5000" in rendered


def test_provision_base_for_multipass_targets_vm_ip() -> None:
    import json
    from multipass import FakeBackend, MultipassClient
    from multipass._backend import CommandResult

    name = "nanofaas-e2e"
    info_payload = {
        "info": {
            name: {
                "state": "Running",
                "ipv4": ["192.168.64.10"],
                "image_release": "",
                "image_hash": "",
                "cpu_count": 4,
                "memory": {},
                "disks": {},
                "mounts": {},
            }
        }
    }
    backend = FakeBackend({
        ("multipass", "info", name, "--format", "json"): CommandResult(
            args=[], returncode=0, stdout=json.dumps(info_payload), stderr=""
        )
    })
    shell = RecordingShell()
    adapter = AnsibleAdapter(
        repo_root=Path("/repo"),
        shell=shell,
        multipass_client=MultipassClient(backend=backend),
    )
    request = VmRequest(lifecycle="multipass", name=name, user="ubuntu")

    adapter.provision_base(request, dry_run=False)

    rendered = " ".join(shell.commands[-1])
    assert "-i" in shell.commands[-1]
    assert "192.168.64.10," in rendered
    assert "localhost," not in rendered
