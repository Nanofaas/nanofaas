from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from workflow_tasks.components.context import ScenarioExecutionContext
from workflow_tasks.components.namespace import (
    NAMESPACE_INSTALL,
    NAMESPACE_UNINSTALL,
    namespace_release_name,
    plan_install_namespace,
    plan_uninstall_namespace,
)
from workflow_tasks.vm.models import VmRequest


@dataclass
class _RS:
    namespace: str | None
    functions: list


def _ctx(*, namespace: str | None = None, rs_namespace: str | None = None) -> ScenarioExecutionContext:
    return ScenarioExecutionContext(
        repo_root=Path("/repo"),
        scenario_name="s",
        runtime="java",
        namespace=namespace,
        local_registry="localhost:5000",
        resolved_scenario=_RS(namespace=rs_namespace, functions=[]) if rs_namespace is not None else None,
        vm_request=VmRequest(lifecycle="multipass", name="nanofaas-e2e", user="ubuntu"),
        cleanup_vm=True,
    )


def test_namespace_release_name() -> None:
    assert namespace_release_name("foo") == "foo-namespace"


def test_install_uses_explicit_namespace() -> None:
    ops = plan_install_namespace(_ctx(namespace="myns"))
    rendered = " ".join(ops[0].argv)
    assert "myns-namespace" in rendered
    assert "namespace.name=myns" in rendered


def test_install_falls_back_to_resolved_then_default() -> None:
    assert "resns-namespace" in " ".join(plan_install_namespace(_ctx(rs_namespace="resns"))[0].argv)
    assert "nanofaas-e2e-namespace" in " ".join(plan_install_namespace(_ctx())[0].argv)


def test_uninstall_targets_namespace_release() -> None:
    rendered = " ".join(plan_uninstall_namespace(_ctx(namespace="myns"))[0].argv)
    assert "uninstall" in rendered
    assert "myns-namespace" in rendered


def test_component_definitions_wire_planners() -> None:
    assert NAMESPACE_INSTALL.planner is plan_install_namespace
    assert NAMESPACE_UNINSTALL.planner is plan_uninstall_namespace
