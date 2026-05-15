from __future__ import annotations

from dataclasses import dataclass, field

from workflow_tasks.workflow.events import WorkflowContext, WorkflowEvent, WorkflowSink
from workflow_tasks.workflow.models import TaskDefinition, TaskRun, WorkflowRun, WorkflowState


def utc_now():
    from datetime import UTC, datetime
    return datetime.now(UTC)


@dataclass(slots=True)
class TuiPhaseSnapshot:
    label: str
    task_id: str | None = None
    parent_task_id: str | None = None
    status: WorkflowState = "pending"
    detail: str = ""
    started_at: float | None = None
    finished_at: float | None = None
    children: list["TuiPhaseSnapshot"] = field(default_factory=list)


@dataclass(slots=True)
class TuiWorkflowSnapshot:
    phases: list[TuiPhaseSnapshot]
    logs: list[str]
    show_logs: bool


__all__ = [
    "utc_now",
    "WorkflowState", "WorkflowRun", "TaskDefinition", "TaskRun",
    "TuiPhaseSnapshot", "TuiWorkflowSnapshot",
    "WorkflowContext", "WorkflowEvent", "WorkflowSink",
]
