"""Workflow event types — WorkflowEvent, WorkflowContext, WorkflowSink.

These are the data types shared between event producers (runners) and the
event renderer / sink layer.
"""
from __future__ import annotations

from contextlib import AbstractContextManager
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Protocol


def _utc_now() -> datetime:
    return datetime.now(UTC)


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
    at: datetime = field(default_factory=_utc_now)
    flow_run_id: str | None = None
    task_id: str | None = None
    parent_task_id: str | None = None
    task_run_id: str | None = None
    title: str = ""
    detail: str = ""
    stream: str = "stdout"
    line: str = ""


class WorkflowSink(Protocol):
    """Event receiver for workflow progress."""

    def emit(self, event: "WorkflowEvent") -> None: ...

    def status(self, label: str) -> AbstractContextManager[None]: ...
