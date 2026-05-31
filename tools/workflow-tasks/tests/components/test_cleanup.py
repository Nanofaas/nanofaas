from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from workflow_tasks.components import cleanup as cl
from workflow_tasks.components.context import ScenarioExecutionContext
from workflow_tasks.vm.models import VmRequest


@dataclass
class _RS:
    namespace: str | None
    functions: list


def _ctx(*, lifecycle: str = "multipass", name: str = "nanofaas-e2e", host: str | None = None) -> ScenarioExecutionContext:
    return ScenarioExecutionContext(
        repo_root=Path("/repo"),
        scenario_name="k3s-junit-curl",
        runtime="java",
        namespace="nf",
        local_registry="localhost:5000",
        resolved_scenario=_RS(namespace="nf", functions=[]),
        vm_request=VmRequest(lifecycle=lifecycle, name=name, user="ubuntu", host=host),
        cleanup_vm=True,
    )


def test_uninstall_control_plane_uses_helm_uninstall() -> None:
    ops = cl.plan_uninstall_control_plane(_ctx())
    argv = ops[0].argv
    assert argv[0] == "helm" and "uninstall" in argv
    assert "-n" in argv and "nf" in argv
    assert ops[0].execution_target == "vm"


def test_vm_down_multipass_deletes_vm() -> None:
    ops = cl.plan_vm_down(_ctx(lifecycle="multipass", name="myvm"))
    rendered = " ".join(ops[0].argv)
    assert "multipass" in rendered and "delete" in rendered and "myvm" in rendered


def test_vm_down_external_skips_teardown() -> None:
    ops = cl.plan_vm_down(_ctx(lifecycle="external", host="vm.test"))
    rendered = " ".join(ops[0].argv)
    assert "Skipping" in rendered or ops[0].argv[0] == "echo"


def test_component_definitions_present() -> None:
    assert cl.UNINSTALL_CONTROL_PLANE.component_id == "cleanup.uninstall_control_plane"
    assert cl.VM_DOWN.component_id == "vm.down"
    assert cl.VERIFY_CLI_PLATFORM_STATUS_FAILS.component_id == "cleanup.verify_cli_platform_status_fails"
