from __future__ import annotations

from types import MappingProxyType

from controlplane_tool.tasks.adapters import operation_to_task_spec
from controlplane_tool.scenario.components.operations import RemoteCommandOperation


def test_remote_command_operation_converts_to_task_spec() -> None:
    operation = RemoteCommandOperation(
        operation_id="images.build",
        summary="Build image",
        argv=("docker", "build", "."),
        env=MappingProxyType({"A": "B"}),
        execution_target="vm",
    )

    task = operation_to_task_spec(operation)

    assert task.task_id == "images.build"
    assert task.summary == "Build image"
    assert task.argv == ("docker", "build", ".")
    assert task.env == {"A": "B"}
    assert task.target == "vm"
