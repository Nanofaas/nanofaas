from __future__ import annotations

from types import MappingProxyType

from controlplane_tool.tasks.adapters import operation_to_task_spec, task_result_to_shell_result
from controlplane_tool.tasks.models import CommandTaskSpec, TaskResult
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


def test_task_result_to_shell_result_preserves_command_and_output() -> None:
    task = CommandTaskSpec(task_id="x", summary="X", argv=("echo", "hi"), env={"A": "B"})
    result = TaskResult(task_id="x", status="passed", return_code=0, stdout="hi\n", stderr="warn\n")

    shell_result = task_result_to_shell_result(task, result, dry_run=True)

    assert shell_result.command == ["echo", "hi"]
    assert shell_result.env == {"A": "B"}
    assert shell_result.return_code == 0
    assert shell_result.stdout == "hi\n"
    assert shell_result.stderr == "warn\n"
    assert shell_result.dry_run is True


def test_task_result_to_shell_result_maps_missing_failed_return_code_to_failure() -> None:
    task = CommandTaskSpec(task_id="x", summary="X", argv=("false",))
    result = TaskResult(task_id="x", status="failed", return_code=None)

    shell_result = task_result_to_shell_result(task, result)

    assert shell_result.return_code == 1


def test_task_result_to_shell_result_maps_missing_passed_return_code_to_success() -> None:
    task = CommandTaskSpec(task_id="x", summary="X", argv=("true",))
    result = TaskResult(task_id="x", status="passed", return_code=None)

    shell_result = task_result_to_shell_result(task, result)

    assert shell_result.return_code == 0
