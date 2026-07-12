from dataclasses import replace
from pathlib import Path
from typing import Any, cast

from workflow_tasks.core.workflow import Workflow
from workflow_tasks.execution.bindings import RoleBindings, RoleBoundCommandTaskExecutor
from workflow_tasks.tasks.command_task import CommandTask
from workflow_tasks.tasks.models import CommandTaskSpec


def workflow_from_specs(
    specs: tuple[CommandTaskSpec, ...],
    bindings: RoleBindings,
    *,
    cwd: Path | None = None,
) -> Workflow:
    executor = cast(Any, RoleBoundCommandTaskExecutor(bindings))
    return Workflow(
        tasks=[
            CommandTask(
                task_id=spec.task_id,
                title=spec.summary,
                spec=replace(spec, cwd=cwd) if cwd is not None and spec.cwd is None else spec,
                executor=executor,
            )
            for spec in specs
        ]
    )
