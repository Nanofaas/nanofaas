from __future__ import annotations

from controlplane_tool.tasks.models import CommandTaskSpec, TaskResult
from controlplane_tool.workflow.workflow_models import WorkflowEvent


def task_started_event(task: CommandTaskSpec, *, flow_id: str) -> WorkflowEvent:
    return WorkflowEvent(
        kind="task.running",
        flow_id=flow_id,
        task_id=task.task_id,
        title=task.summary,
    )


def task_result_event(
    task: CommandTaskSpec,
    result: TaskResult,
    *,
    flow_id: str,
) -> WorkflowEvent:
    return WorkflowEvent(
        kind=_result_event_kind(result),
        flow_id=flow_id,
        task_id=task.task_id,
        title=task.summary,
        detail=_result_event_detail(result),
    )


def _result_event_kind(result: TaskResult) -> str:
    if result.ok:
        return "task.completed"
    if result.status == "skipped":
        return "task.completed"
    return "task.failed"


def _result_event_detail(result: TaskResult) -> str:
    return result.stderr.strip() or result.stdout.strip() or result.status
