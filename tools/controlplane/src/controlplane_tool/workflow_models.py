from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime


def utc_now() -> datetime:
    return datetime.now(UTC)


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
    task_run_id: str | None = None


@dataclass(slots=True, frozen=True)
class WorkflowEvent:
    kind: str
    flow_id: str
    at: datetime = field(default_factory=utc_now)
    flow_run_id: str | None = None
    task_id: str | None = None
    task_run_id: str | None = None
    title: str = ""
    detail: str = ""
    stream: str = "stdout"
    line: str = ""
