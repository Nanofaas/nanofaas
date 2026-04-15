from __future__ import annotations

from contextlib import AbstractContextManager
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Literal, Protocol


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


@dataclass(slots=True, frozen=True)
class WorkflowContext:
    flow_id: str = "interactive.console"
    flow_run_id: str | None = None
    task_id: str | None = None
    parent_task_id: str | None = None
    task_run_id: str | None = None


@dataclass(slots=True, frozen=True)
class WorkflowEvent:
    kind: str
    flow_id: str
    at: datetime = field(default_factory=utc_now)
    flow_run_id: str | None = None
    task_id: str | None = None
    parent_task_id: str | None = None
    task_run_id: str | None = None
    title: str = ""
    detail: str = ""
    stream: str = "stdout"
    line: str = ""


class WorkflowSink(Protocol):
    """Event receiver for workflow progress — implemented by TUI, console, and test fakes."""

    def emit(self, event: "WorkflowEvent") -> None: ...

    def status(self, label: str) -> AbstractContextManager[None]: ...
