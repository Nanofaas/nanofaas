from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Literal


def utc_now() -> datetime:
    return datetime.now(UTC)


WorkflowState = Literal["pending", "running", "success", "failed", "cancelled"]


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
