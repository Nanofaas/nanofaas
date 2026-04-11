from __future__ import annotations

from controlplane_tool.scenario_components.models import ScenarioComponentDefinition
from controlplane_tool.scenario_components.operations import (
    RemoteCommandOperation,
    ScenarioOperation,
)


def test_component_model_has_stable_task_id_and_summary() -> None:
    component = ScenarioComponentDefinition(
        component_id="vm.ensure_running",
        summary="Ensure VM is running",
    )

    assert component.component_id == "vm.ensure_running"
    assert component.summary == "Ensure VM is running"


def test_component_planner_returns_typed_operations_not_shell_strings() -> None:
    operation = RemoteCommandOperation(
        operation_id="vm.ensure_running",
        summary="Ensure VM is running",
        argv=("multipass", "launch", "nanofaas-e2e"),
    )

    assert isinstance(operation, ScenarioOperation)
    assert operation.operation_id == "vm.ensure_running"
    assert operation.summary == "Ensure VM is running"
    assert operation.argv == ("multipass", "launch", "nanofaas-e2e")
    assert all(isinstance(part, str) for part in operation.argv)
