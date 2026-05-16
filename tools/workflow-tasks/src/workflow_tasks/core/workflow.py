from __future__ import annotations

from dataclasses import dataclass, field

from workflow_tasks.core.task import Task
from workflow_tasks.workflow.reporting import workflow_step


@dataclass
class Workflow:
    """Sequential task executor with optional always-run cleanup tasks.

    tasks run in order; execution stops at the first failure.
    cleanup_tasks always run, even after a failure in tasks.
    """

    tasks: list[Task]
    cleanup_tasks: list[Task] = field(default_factory=list)

    @property
    def task_ids(self) -> list[str]:
        """Stable list of task IDs in execution order. Used by TUI for dry-run planning."""
        return [t.task_id for t in self.tasks + self.cleanup_tasks]

    def run(self) -> None:
        main_error: BaseException | None = None

        for task in self.tasks:
            try:
                with workflow_step(task_id=task.task_id, title=task.title):
                    task.run()
            except BaseException as exc:
                main_error = exc
                break

        cleanup_errors: list[str] = []
        for task in self.cleanup_tasks:
            try:
                with workflow_step(task_id=task.task_id, title=task.title):
                    task.run()
            except Exception as exc:
                cleanup_errors.append(str(exc))

        if main_error is not None:
            if cleanup_errors:
                combined = f"{main_error}\n\nCleanup errors:\n" + "\n".join(cleanup_errors)
                raise RuntimeError(combined) from main_error
            raise main_error

        if cleanup_errors:
            raise RuntimeError("Cleanup failed:\n" + "\n".join(cleanup_errors))
