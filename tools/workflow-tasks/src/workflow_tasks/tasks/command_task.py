from __future__ import annotations

from dataclasses import dataclass

from workflow_tasks.tasks.adapters import RemoteCommandOperationLike, operation_to_task_spec
from workflow_tasks.tasks.executors import HostCommandTaskExecutor, VmCommandTaskExecutor
from workflow_tasks.tasks.models import CommandTaskSpec, TaskResult

CommandExecutor = HostCommandTaskExecutor | VmCommandTaskExecutor


@dataclass
class CommandTask:
    """A composable Task that runs a single CommandTaskSpec via an executor.

    Satisfies the workflow_tasks.Task protocol. Raises RuntimeError on failure so
    Workflow.run() stops the pipeline (and triggers cleanup_tasks).
    """

    task_id: str
    title: str
    spec: CommandTaskSpec
    executor: CommandExecutor

    def run(self) -> TaskResult:
        result = self.executor.run(self.spec)
        if result.status != "passed":
            detail = result.stderr.strip() or result.stdout.strip() or "no output"
            raise RuntimeError(
                f"{self.task_id} failed (exit {result.return_code}): {detail}"
            )
        return result


def command_task_from_operation(
    operation: RemoteCommandOperationLike,
    executor: CommandExecutor,
    *,
    title: str | None = None,
    remote_dir: str | None = None,
) -> CommandTask:
    """Build a CommandTask from a RemoteCommandOperation-like object.

    Converts via operation_to_task_spec; title defaults to the operation summary,
    task_id is the operation_id.
    """
    spec = operation_to_task_spec(operation, remote_dir=remote_dir)
    return CommandTask(
        task_id=spec.task_id,
        title=title if title is not None else spec.summary,
        spec=spec,
        executor=executor,
    )
