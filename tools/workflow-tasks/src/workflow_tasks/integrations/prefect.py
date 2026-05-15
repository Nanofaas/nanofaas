from __future__ import annotations

from collections.abc import Callable

from workflow_tasks.workflow.event_builders import build_log_event, build_task_event
from workflow_tasks.workflow.events import WorkflowContext, WorkflowEvent

_PREFECT_STATE_TO_EVENT_KIND = {
    "cancelled": "task.cancelled",
    "completed": "task.completed",
    "crashed": "task.failed",
    "failed": "task.failed",
    "pending": "task.pending",
    "running": "task.running",
    "scheduled": "task.pending",
}


def normalize_task_state(
    *,
    flow_id: str,
    task_id: str,
    state_name: str,
    flow_run_id: str | None = None,
    parent_task_id: str | None = None,
    task_run_id: str | None = None,
    title: str | None = None,
    detail: str = "",
    context: WorkflowContext | None = None,
) -> WorkflowEvent:
    kind = _PREFECT_STATE_TO_EVENT_KIND.get(state_name.strip().lower(), "task.updated")
    active = context or WorkflowContext()
    return build_task_event(
        kind=kind,
        flow_id=flow_id or active.flow_id,
        flow_run_id=flow_run_id or active.flow_run_id,
        task_id=task_id if task_id is not None else active.task_id,
        parent_task_id=parent_task_id if parent_task_id is not None else active.parent_task_id,
        task_run_id=task_run_id or active.task_run_id,
        title=title or task_id or "",
        detail=detail or state_name,
        context=context,
    )


class PrefectEventBridge:
    def __init__(self, emit: Callable[[WorkflowEvent], None] | None = None) -> None:
        self._emit = emit or (lambda event: None)

    def emit_task_state(
        self,
        *,
        flow_id: str,
        task_id: str,
        state_name: str,
        flow_run_id: str | None = None,
        task_run_id: str | None = None,
        title: str | None = None,
        detail: str = "",
    ) -> WorkflowEvent:
        event = normalize_task_state(
            flow_id=flow_id, flow_run_id=flow_run_id, task_id=task_id,
            task_run_id=task_run_id, state_name=state_name, title=title, detail=detail,
        )
        self._emit(event)
        return event

    def emit_log(
        self,
        *,
        flow_id: str,
        line: str,
        flow_run_id: str | None = None,
        task_id: str | None = None,
        task_run_id: str | None = None,
        stream: str = "stdout",
    ) -> WorkflowEvent:
        event = build_log_event(
            flow_id=flow_id, flow_run_id=flow_run_id, task_id=task_id,
            task_run_id=task_run_id, stream=stream, line=line,
        )
        self._emit(event)
        return event
