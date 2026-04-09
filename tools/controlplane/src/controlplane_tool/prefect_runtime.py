from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, datetime
import logging
import os
from typing import TypeVar
from uuid import uuid4

from controlplane_tool.prefect_models import FlowRunResult

T = TypeVar("T")


def _now_utc() -> datetime:
    return datetime.now(UTC)


def _generated_flow_run_id() -> str:
    return str(uuid4())


def _prefect_backend_name() -> str:
    return "prefect-local" if not os.getenv("PREFECT_API_URL") else "prefect-api"


def _run_without_prefect(
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
            orchestrator_backend="none",
            started_at=started_at,
            finished_at=_now_utc(),
            error=str(exc),
        )
    return FlowRunResult.completed(
        flow_id=flow_id,
        flow_run_id=flow_run_id,
        orchestrator_backend="none",
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
    try:
        from prefect import flow as prefect_flow
        from prefect.runtime import flow_run as prefect_flow_run
        from prefect.settings import PREFECT_LOGGING_LEVEL, temporary_settings
    except ImportError:
        return _run_without_prefect(flow_id, flow_fn, *args, **kwargs)

    started_at = _now_utc()
    captured_flow_run_id = _generated_flow_run_id()
    logging.getLogger("prefect").disabled = True

    @prefect_flow(name=flow_id)
    def _prefect_wrapper() -> T:
        nonlocal captured_flow_run_id
        runtime_flow_run_id = getattr(prefect_flow_run, "id", None)
        if runtime_flow_run_id:
            captured_flow_run_id = str(runtime_flow_run_id)
        return flow_fn(*args, **kwargs)

    try:
        with temporary_settings({PREFECT_LOGGING_LEVEL: "WARNING"}):
            result = _prefect_wrapper()
    except Exception as exc:
        return FlowRunResult.failed(
            flow_id=flow_id,
            flow_run_id=captured_flow_run_id,
            orchestrator_backend=_prefect_backend_name(),
            started_at=started_at,
            finished_at=_now_utc(),
            error=str(exc),
        )

    return FlowRunResult.completed(
        flow_id=flow_id,
        flow_run_id=captured_flow_run_id,
        orchestrator_backend=_prefect_backend_name(),
        started_at=started_at,
        finished_at=_now_utc(),
        result=result,
    )
