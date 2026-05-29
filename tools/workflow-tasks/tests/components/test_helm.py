from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from workflow_tasks.components import helm as helm_mod
from workflow_tasks.components.context import ScenarioExecutionContext
from workflow_tasks.loadtest.two_vm import LOADTEST_SCENARIOS
from workflow_tasks.vm.models import VmRequest


@dataclass
class _RS:
    namespace: str | None
    functions: list


def _ctx(scenario_name: str) -> ScenarioExecutionContext:
    return ScenarioExecutionContext(
        repo_root=Path("/repo"),
        scenario_name=scenario_name,
        runtime="java",
        namespace="ns",
        local_registry="localhost:5000",
        resolved_scenario=_RS(namespace="ns", functions=[]),
        vm_request=VmRequest(lifecycle="multipass", name="nanofaas-e2e", user="ubuntu"),
        cleanup_vm=True,
    )


def test_helm_component_definitions_present() -> None:
    assert helm_mod.HELM_DEPLOY_CONTROL_PLANE.component_id == "helm.deploy_control_plane"
    assert helm_mod.HELM_DEPLOY_FUNCTION_RUNTIME.component_id == "helm.deploy_function_runtime"


def test_control_plane_planner_runs_for_loadtest_scenario() -> None:
    scenario = next(iter(LOADTEST_SCENARIOS))
    ops = helm_mod.HELM_DEPLOY_CONTROL_PLANE.planner(_ctx(scenario))
    assert len(ops) >= 1


def test_control_plane_planner_runs_for_plain_scenario() -> None:
    ops = helm_mod.HELM_DEPLOY_CONTROL_PLANE.planner(_ctx("k3s-junit-curl"))
    assert len(ops) >= 1


def test_function_runtime_planner_runs() -> None:
    ops = helm_mod.HELM_DEPLOY_FUNCTION_RUNTIME.planner(_ctx("k3s-junit-curl"))
    assert len(ops) >= 1


def test_loadtest_scenario_exposes_node_port() -> None:
    scenario = next(iter(LOADTEST_SCENARIOS))
    ops = helm_mod.HELM_DEPLOY_CONTROL_PLANE.planner(_ctx(scenario))
    # The RemoteCommandOperation argv should contain NodePort-related --set args
    argv = ops[0].argv
    assert any("NodePort" in arg for arg in argv)


def test_plain_scenario_no_node_port() -> None:
    ops = helm_mod.HELM_DEPLOY_CONTROL_PLANE.planner(_ctx("k3s-junit-curl"))
    argv = ops[0].argv
    assert not any("NodePort" in arg for arg in argv)


def test_control_plane_helm_values_contains_namespace() -> None:
    values = helm_mod.control_plane_helm_values(
        namespace="myns",
        control_plane_image="localhost:5000/control-plane:latest",
    )
    assert values["namespace.name"] == "myns"


def test_function_runtime_helm_values_parses_image() -> None:
    values = helm_mod.function_runtime_helm_values(
        function_runtime_image="localhost:5000/runtime:v1.2.3"
    )
    assert values["functionRuntime.image.repository"] == "localhost:5000/runtime"
    assert values["functionRuntime.image.tag"] == "v1.2.3"
