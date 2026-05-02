"""Workflow data models for the nanofaas control-plane tool.

WorkflowEvent, WorkflowContext, WorkflowSink were moved to tui_toolkit.events
and are re-exported here for backward compatibility.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Literal

from tui_toolkit.events import WorkflowContext, WorkflowEvent, WorkflowSink


def utc_now() -> datetime:
    return datetime.now(UTC)


WorkflowState = Literal["pending", "running", "success", "failed", "cancelled"]


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


@dataclass(slots=True, frozen=True)
class WorkflowRun:
    flow_id: str
    flow_run_id: str
    status: str = "pending"
    orchestrator_backend: str = "none"
    started_at: datetime | None = None
    finished_at: datetime | None = None


@dataclass(slots=True, frozen=True)
class TaskDefinition:
    task_id: str
    title: str = ""
    detail: str = ""


@dataclass(slots=True, frozen=True)
class TaskRun:
    flow_id: str
    task_id: str
    task_run_id: str
    status: str = "pending"
    title: str = ""
    detail: str = ""


__all__ = [
    "utc_now",
    "WorkflowState",
    "TuiPhaseSnapshot", "TuiWorkflowSnapshot",
    "WorkflowRun", "TaskDefinition", "TaskRun",
    # re-exported from tui_toolkit.events
    "WorkflowContext", "WorkflowEvent", "WorkflowSink",
]
