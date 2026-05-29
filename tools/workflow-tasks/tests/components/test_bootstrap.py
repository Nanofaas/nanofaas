from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from workflow_tasks.components import bootstrap as bs
from workflow_tasks.components.context import ScenarioExecutionContext
from workflow_tasks.vm.models import VmRequest


@dataclass
class _RS:
    namespace: str | None
    functions: list


def _ctx(*, lifecycle: str = "external", host: str | None = "vm.test") -> ScenarioExecutionContext:
    return ScenarioExecutionContext(
        repo_root=Path("/repo"),
        scenario_name="k3s-junit-curl",
        runtime="java",
        namespace="nf",
        local_registry="localhost:5000",
        resolved_scenario=_RS(namespace="nf", functions=[]),
        vm_request=VmRequest(lifecycle=lifecycle, name="nanofaas-e2e", user="ubuntu", host=host),
        cleanup_vm=True,
    )


def test_provision_base_uses_ops_ansible_playbook_path() -> None:
    ops = bs.plan_vm_provision_base(_ctx())
    rendered = " ".join(str(a) for a in ops[0].argv)
    assert "ops/ansible/playbooks/" in rendered
    assert "provision-base" in rendered


def test_provision_base_sets_ansible_config_env() -> None:
    ops = bs.plan_vm_provision_base(_ctx())
    env = dict(ops[0].env)
    assert any("ops/ansible/ansible.cfg" in str(v) for v in env.values())


def test_k3s_install_planner_runs() -> None:
    assert len(bs.plan_k3s_install(_ctx())) >= 1


def test_component_definitions_present() -> None:
    assert bs.VM_ENSURE_RUNNING.component_id == "vm.ensure_running"
    assert bs.VM_PROVISION_BASE.component_id == "vm.provision_base"
