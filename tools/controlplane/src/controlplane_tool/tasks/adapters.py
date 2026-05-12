from __future__ import annotations

from collections.abc import Mapping
from typing import Protocol

from controlplane_tool.tasks.models import CommandTaskSpec, ExecutionTarget


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
