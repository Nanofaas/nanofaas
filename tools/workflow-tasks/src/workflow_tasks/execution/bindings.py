from __future__ import annotations

from dataclasses import dataclass, replace
from typing import Literal, Protocol

from workflow_tasks.execution.roles import ExecutionRole
from workflow_tasks.tasks.models import CommandTaskSpec, TaskResult


class CommandTaskExecutor(Protocol):
    def run(self, task: CommandTaskSpec, *, dry_run: bool = False) -> TaskResult: ...


@dataclass(frozen=True, slots=True)
class RoleBindings:
    host: CommandTaskExecutor
    stack: CommandTaskExecutor
    loadgen: CommandTaskExecutor | None = None
    cloud: CommandTaskExecutor | None = None

    def executor_for(self, role: ExecutionRole) -> CommandTaskExecutor:
        executor = getattr(self, role)
        if executor is None:
            raise ValueError(f"no executor bound for role {role!r}")
        return executor


class RoleBoundCommandTaskExecutor:
    def __init__(self, bindings: RoleBindings) -> None:
        self._bindings = bindings

    def run(self, task: CommandTaskSpec, *, dry_run: bool = False) -> TaskResult:
        return self._bindings.executor_for(task.execution_role).run(task, dry_run=dry_run)


class RetargetingCommandTaskExecutor:
    """Adapt a legacy target-checking executor to an explicitly bound role."""

    def __init__(
        self,
        executor: CommandTaskExecutor,
        target: Literal["host", "vm"],
    ) -> None:
        self._executor = executor
        self._target = target

    def run(self, task: CommandTaskSpec, *, dry_run: bool = False) -> TaskResult:
        return self._executor.run(replace(task, target=self._target), dry_run=dry_run)
