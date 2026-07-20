from __future__ import annotations

from dataclasses import dataclass

import pytest

from workflow_tasks.execution.bindings import (
    RetargetingCommandTaskExecutor,
    RoleBindings,
    RoleBoundCommandTaskExecutor,
)
from workflow_tasks.tasks.models import CommandTaskSpec, TaskResult


@dataclass
class _RecordingExecutor:
    calls: list[CommandTaskSpec]

    def run(self, task: CommandTaskSpec, *, dry_run: bool = False) -> TaskResult:
        self.calls.append(task)
        return TaskResult(task_id=task.task_id, status="passed", return_code=0)


def test_dispatches_commands_by_logical_role() -> None:
    host = _RecordingExecutor([])
    stack = _RecordingExecutor([])
    executor = RoleBoundCommandTaskExecutor(RoleBindings(host=host, stack=stack))
    task = CommandTaskSpec(
        task_id="stack.inspect",
        summary="Inspect stack",
        argv=("docker", "ps"),
        target="vm",
        role="stack",
    )

    result = executor.run(task)

    assert result.ok
    assert host.calls == []
    assert stack.calls == [task]


def test_loadgen_can_share_stack_executor() -> None:
    host = _RecordingExecutor([])
    stack = _RecordingExecutor([])
    bindings = RoleBindings(host=host, stack=stack, loadgen=stack)
    executor = RoleBoundCommandTaskExecutor(bindings)
    task = CommandTaskSpec(
        task_id="loadgen.run",
        summary="Run load",
        argv=("k6", "run"),
        target="vm",
        role="loadgen",
    )

    executor.run(task)

    assert stack.calls == [task]


def test_legacy_targets_infer_host_and_stack_roles() -> None:
    host = _RecordingExecutor([])
    stack = _RecordingExecutor([])
    executor = RoleBoundCommandTaskExecutor(RoleBindings(host=host, stack=stack))
    host_task = CommandTaskSpec(task_id="host", summary="Host", argv=("true",))
    stack_task = CommandTaskSpec(task_id="stack", summary="Stack", argv=("true",), target="vm")

    executor.run(host_task)
    executor.run(stack_task)

    assert host.calls == [host_task]
    assert stack.calls == [stack_task]


def test_missing_loadgen_binding_fails_clearly() -> None:
    executor = RoleBoundCommandTaskExecutor(
        RoleBindings(host=_RecordingExecutor([]), stack=_RecordingExecutor([]))
    )
    task = CommandTaskSpec(
        task_id="loadgen.run",
        summary="Run load",
        argv=("k6", "run"),
        target="vm",
        role="loadgen",
    )

    with pytest.raises(ValueError, match="no executor bound for role 'loadgen'"):
        executor.run(task)


def test_retargeting_adapter_preserves_role_and_changes_only_legacy_target() -> None:
    delegate = _RecordingExecutor([])
    executor = RetargetingCommandTaskExecutor(delegate, "vm")
    task = CommandTaskSpec(
        task_id="stack.run",
        summary="Run on stack",
        argv=("true",),
        role="stack",
    )

    executor.run(task)

    assert delegate.calls[0].target == "vm"
    assert delegate.calls[0].role == "stack"


def test_role_bindings_resolve_the_cloud_role() -> None:
    host = object()
    cloud = object()
    bindings = RoleBindings(host=host, stack=host, cloud=cloud)  # type: ignore[arg-type]
    assert bindings.executor_for("cloud") is cloud


def test_missing_cloud_binding_raises() -> None:
    host = object()
    bindings = RoleBindings(host=host, stack=host)  # type: ignore[arg-type]
    with pytest.raises(ValueError, match="cloud"):
        bindings.executor_for("cloud")
