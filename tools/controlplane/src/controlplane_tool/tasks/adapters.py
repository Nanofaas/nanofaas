from __future__ import annotations

from collections.abc import Mapping
from typing import Protocol

from controlplane_tool.core.shell_backend import ShellExecutionResult
from controlplane_tool.tasks.models import CommandTaskSpec, ExecutionTarget
from controlplane_tool.tasks.models import TaskResult


class RemoteCommandOperationLike(Protocol):
    @property
    def operation_id(self) -> str: ...

    @property
    def summary(self) -> str: ...

    @property
    def argv(self) -> tuple[str, ...]: ...

    @property
    def env(self) -> Mapping[str, str]: ...

    @property
    def execution_target(self) -> str: ...


def operation_to_task_spec(
    operation: RemoteCommandOperationLike,
    *,
    remote_dir: str | None = None,
) -> CommandTaskSpec:
    target: ExecutionTarget = "vm" if operation.execution_target == "vm" else "host"
    return CommandTaskSpec(
        task_id=operation.operation_id,
        summary=operation.summary,
        argv=tuple(operation.argv),
        target=target,
        env=dict(operation.env),
        remote_dir=remote_dir if target == "vm" else None,
    )


def task_result_to_shell_result(
    task: CommandTaskSpec,
    result: TaskResult,
    *,
    dry_run: bool = False,
) -> ShellExecutionResult:
    return_code = (
        result.return_code
        if result.return_code is not None
        else 0
        if result.status == "passed"
        else 1
    )
    return ShellExecutionResult(
        command=list(task.argv),
        return_code=return_code,
        stdout=result.stdout,
        stderr=result.stderr,
        dry_run=dry_run,
        env=dict(task.env),
    )
