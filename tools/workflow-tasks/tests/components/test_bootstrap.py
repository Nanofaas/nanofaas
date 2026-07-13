from __future__ import annotations

from pathlib import Path

from workflow_tasks.components import bootstrap as bs
from workflow_tasks.components.context import ScenarioExecutionContext
from workflow_tasks.components.operations import RemoteCommandOperation, ScenarioOperation
from workflow_tasks.vm.models import VmLifecycle, VmRequest


def _ctx(
    *, lifecycle: VmLifecycle = "external", host: str | None = "vm.test"
) -> ScenarioExecutionContext:
    return ScenarioExecutionContext(
        repo_root=Path("/repo"),
        scenario_name="k3s-junit-curl",
        runtime="java",
        namespace="nf",
        local_registry="localhost:5000",
        resolved_scenario=None,
        vm_request=VmRequest(lifecycle=lifecycle, name="nanofaas-e2e", user="ubuntu", host=host),
        cleanup_vm=True,
    )


def _remote(operation: ScenarioOperation) -> RemoteCommandOperation:
    assert isinstance(operation, RemoteCommandOperation)
    return operation


def test_provision_base_uses_ops_ansible_playbook_path() -> None:
    ops = bs.plan_vm_provision_base(_ctx())
    rendered = " ".join(str(a) for a in _remote(ops[0]).argv)
    assert "infra/ansible_assets/playbooks/" in rendered
    assert "provision-base" in rendered


def test_provision_base_sets_ansible_config_env() -> None:
    ops = bs.plan_vm_provision_base(_ctx())
    env = dict(_remote(ops[0]).env)
    assert any("infra/ansible_assets/ansible.cfg" in str(v) for v in env.values())


def test_k3s_install_planner_runs() -> None:
    assert len(bs.plan_k3s_install(_ctx())) >= 1


def test_component_definitions_present() -> None:
    assert bs.VM_ENSURE_RUNNING.component_id == "vm.ensure_running"
    assert bs.VM_PROVISION_BASE.component_id == "vm.provision_base"


def test_retarget_bootstrap_ansible_uses_endpoint_port_and_key() -> None:
    context = _ctx(lifecycle="proxmox", host=None)
    operation = _remote(bs.plan_vm_provision_base(context)[0])

    retargeted = bs.retarget_bootstrap_operation(
        operation,
        context=context,
        host="pve.example",
        port=42022,
        private_key=Path("/keys/id_ed25519"),
    )

    argv = list(retargeted.argv)
    assert argv[argv.index("-i") + 1] == "pve.example,"
    assert argv[argv.index("ansible_port=42022") - 1] == "-e"
    assert argv[argv.index("--private-key") + 1] == "/keys/id_ed25519"


def test_retarget_bootstrap_repo_sync_uses_endpoint_port_and_key() -> None:
    context = _ctx(lifecycle="proxmox", host=None)
    operation = _remote(bs.plan_repo_sync_to_vm(context)[0])

    retargeted = bs.retarget_bootstrap_operation(
        operation,
        context=context,
        host="pve.example",
        port=42022,
        private_key=Path("/keys/id_ed25519"),
    )

    argv = list(retargeted.argv)
    assert argv[0] == "rsync"
    assert (
        "ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -p 42022 -i /keys/id_ed25519"
        in argv
    )
    assert argv[-1] == "ubuntu@pve.example:/home/ubuntu/nanofaas/"


def test_retarget_bootstrap_azure_uses_default_ssh_port() -> None:
    context = _ctx(lifecycle="azure", host=None)
    operation = _remote(bs.plan_repo_sync_to_vm(context)[0])

    retargeted = bs.retarget_bootstrap_operation(
        operation,
        context=context,
        host="20.30.40.50",
        private_key=Path("/keys/azure"),
    )

    ssh_command = list(retargeted.argv)[-3]
    assert " -p " not in ssh_command
    assert "-i /keys/azure" in ssh_command
    assert retargeted.argv[-1] == "ubuntu@20.30.40.50:/home/ubuntu/nanofaas/"
