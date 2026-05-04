"""Workflow event helpers for the control-plane tool.

The generic builders (build_task_event, build_phase_event, build_log_event)
are re-exported from tui_toolkit.workflow. The Prefect-specific
normalize_task_state stays here because the Prefect state→event mapping is
domain knowledge.
"""
from __future__ import annotations

from tui_toolkit.workflow import build_log_event, build_phase_event, build_task_event

from controlplane_tool.workflow.workflow_models import WorkflowContext, WorkflowEvent

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
    """Map a Prefect state name to a WorkflowEvent kind, then building the event."""
    kind = _PREFECT_STATE_TO_EVENT_KIND.get(state_name.strip().lower(), "task.updated")
    active = context or WorkflowContext()
    resolved_flow_id = flow_id or active.flow_id
    resolved_flow_run_id = flow_run_id or active.flow_run_id
    resolved_task_id = task_id if task_id is not None else active.task_id
    resolved_parent_task_id = parent_task_id if parent_task_id is not None else active.parent_task_id
    resolved_task_run_id = task_run_id or active.task_run_id
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


__all__ = [
    "build_log_event", "build_phase_event", "build_task_event",
    "normalize_task_state",
]
