from __future__ import annotations

from controlplane_tool.workflow_models import WorkflowContext, WorkflowEvent

_PREFECT_STATE_TO_EVENT_KIND = {
    "cancelled": "task.cancelled",
    "completed": "task.completed",
    "crashed": "task.failed",
    "failed": "task.failed",
    "pending": "task.pending",
    "running": "task.running",
    "scheduled": "task.pending",
}


def _event_context(
    *,
    flow_id: str | None,
    flow_run_id: str | None,
    task_id: str | None,
    parent_task_id: str | None,
    task_run_id: str | None,
    context: WorkflowContext | None,
    inherit_task_id: bool = True,
) -> tuple[str, str | None, str | None, str | None, str | None]:
    active = context or WorkflowContext()
    resolved_task_id = task_id if task_id is not None else active.task_id if inherit_task_id else None
    resolved_parent_task_id = parent_task_id if parent_task_id is not None else active.parent_task_id
    return (
        flow_id or active.flow_id,
        flow_run_id or active.flow_run_id,
        resolved_task_id,
        resolved_parent_task_id,
        task_run_id or active.task_run_id,
    )


def build_task_event(
    *,
    kind: str,
    flow_id: str | None = None,
    flow_run_id: str | None = None,
    task_id: str | None = None,
    parent_task_id: str | None = None,
    task_run_id: str | None = None,
    title: str = "",
    detail: str = "",
    context: WorkflowContext | None = None,
) -> WorkflowEvent:
    resolved_flow_id, resolved_flow_run_id, resolved_task_id, resolved_parent_task_id, resolved_task_run_id = _event_context(
        flow_id=flow_id,
        flow_run_id=flow_run_id,
        task_id=task_id,
        parent_task_id=parent_task_id,
        task_run_id=task_run_id,
        context=context,
    )
    return WorkflowEvent(
        kind=kind,
        flow_id=resolved_flow_id,
        flow_run_id=resolved_flow_run_id,
        task_id=resolved_task_id,
        parent_task_id=resolved_parent_task_id,
        task_run_id=resolved_task_run_id,
        title=title or resolved_task_id or kind,
        detail=detail,
    )


def build_phase_event(
    label: str,
    *,
    flow_id: str | None = None,
    flow_run_id: str | None = None,
    context: WorkflowContext | None = None,
) -> WorkflowEvent:
    resolved_flow_id, resolved_flow_run_id, resolved_task_id, resolved_parent_task_id, resolved_task_run_id = _event_context(
        flow_id=flow_id,
        flow_run_id=flow_run_id,
        task_id=None,
        parent_task_id=None,
        task_run_id=None,
        context=context,
    )
    return WorkflowEvent(
        kind="phase.started",
        flow_id=resolved_flow_id,
        flow_run_id=resolved_flow_run_id,
        task_id=resolved_task_id,
        parent_task_id=resolved_parent_task_id,
        task_run_id=resolved_task_run_id,
        title=label,
    )


def build_log_event(
    *,
    line: str,
    flow_id: str | None = None,
    flow_run_id: str | None = None,
    task_id: str | None = None,
    parent_task_id: str | None = None,
    task_run_id: str | None = None,
    stream: str = "stdout",
    context: WorkflowContext | None = None,
) -> WorkflowEvent:
    resolved_flow_id, resolved_flow_run_id, resolved_task_id, resolved_parent_task_id, resolved_task_run_id = _event_context(
        flow_id=flow_id,
        flow_run_id=flow_run_id,
        task_id=task_id,
        parent_task_id=parent_task_id,
        task_run_id=task_run_id,
        context=context,
    )
    return WorkflowEvent(
        kind="log.line",
        flow_id=resolved_flow_id,
        flow_run_id=resolved_flow_run_id,
        task_id=resolved_task_id,
        parent_task_id=resolved_parent_task_id,
        task_run_id=resolved_task_run_id,
        stream=stream,
        line=line,
    )


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
    resolved_flow_id, resolved_flow_run_id, resolved_task_id, resolved_parent_task_id, resolved_task_run_id = _event_context(
        flow_id=flow_id,
        flow_run_id=flow_run_id,
        task_id=task_id,
        parent_task_id=parent_task_id,
        task_run_id=task_run_id,
        context=context,
    )
    return WorkflowEvent(
        kind=kind,
        flow_id=resolved_flow_id,
        flow_run_id=resolved_flow_run_id,
        task_id=resolved_task_id,
        parent_task_id=resolved_parent_task_id,
        task_run_id=resolved_task_run_id,
        title=title or resolved_task_id or task_id,
        detail=detail or state_name,
    )
