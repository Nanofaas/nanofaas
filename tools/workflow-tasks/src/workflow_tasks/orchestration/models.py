from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from typing import Generic, TypeVar

T = TypeVar("T")


@dataclass(slots=True)
class FlowRunResult(Generic[T]):
    flow_id: str
    flow_run_id: str
    orchestrator_backend: str
    started_at: datetime
    finished_at: datetime
    status: str
    result: T | None = None
    error: str | None = None

    @classmethod
    def completed(
        cls,
        *,
        flow_id: str,
        flow_run_id: str,
        orchestrator_backend: str,
        started_at: datetime,
        finished_at: datetime,
        result: T | None = None,
    ) -> "FlowRunResult[T]":
        return cls(
            flow_id=flow_id,
            flow_run_id=flow_run_id,
            orchestrator_backend=orchestrator_backend,
            started_at=started_at,
            finished_at=finished_at,
            status="completed",
            result=result,
        )

    @classmethod
    def failed(
        cls,
        *,
        flow_id: str,
        flow_run_id: str,
        orchestrator_backend: str,
        started_at: datetime,
        finished_at: datetime,
        error: str,
    ) -> "FlowRunResult[T]":
        return cls(
            flow_id=flow_id,
            flow_run_id=flow_run_id,
            orchestrator_backend=orchestrator_backend,
            started_at=started_at,
            finished_at=finished_at,
            status="failed",
            error=error,
        )


@dataclass(slots=True)
class LocalFlowDefinition(Generic[T]):
    flow_id: str
    task_ids: list[str]
    run: Callable[[], T]
