from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, datetime
import traceback
from typing import TypeVar
from uuid import uuid4

from workflow_tasks.orchestration.models import FlowRunResult

T = TypeVar("T")


def _now_utc() -> datetime:
    return datetime.now(UTC)


def _generated_flow_run_id() -> str:
    return str(uuid4())


def _format_flow_error(exc: Exception) -> str:
    return f"{exc}\n{traceback.format_exc()}"


def _run_direct(
    flow_id: str,
    flow_fn: Callable[..., T],
    *args: object,
    **kwargs: object,
) -> FlowRunResult[T]:
    started_at = _now_utc()
    flow_run_id = _generated_flow_run_id()
    try:
        result = flow_fn(*args, **kwargs)
    except Exception as exc:
        return FlowRunResult.failed(
            flow_id=flow_id,
            flow_run_id=flow_run_id,
            orchestrator_backend="direct",
            started_at=started_at,
            finished_at=_now_utc(),
            error=_format_flow_error(exc),
        )
    return FlowRunResult.completed(
        flow_id=flow_id,
        flow_run_id=flow_run_id,
        orchestrator_backend="direct",
        started_at=started_at,
        finished_at=_now_utc(),
        result=result,
    )


def run_local_flow(
    flow_id: str,
    flow_fn: Callable[..., T],
    *args: object,
    **kwargs: object,
) -> FlowRunResult[T]:
    return _run_direct(flow_id, flow_fn, *args, **kwargs)
