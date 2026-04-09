from __future__ import annotations

from contextlib import contextmanager
from collections.abc import Callable
from datetime import UTC, datetime
import os
from typing import Generator
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


@contextmanager
def _quiet_prefect_runtime() -> Generator[None, None, None]:
    from prefect.events.clients import NullEventsClient
    from prefect.events.worker import EventsWorker
    from prefect.logging.configuration import setup_logging
    from prefect.settings import (
        PREFECT_LOGGING_LEVEL,
        PREFECT_LOGGING_LOG_PRINTS,
        PREFECT_LOGGING_TO_API_ENABLED,
        PREFECT_SERVER_ALLOW_EPHEMERAL_MODE,
        temporary_settings,
    )

    previous_override = getattr(EventsWorker, "_client_override", None)
    try:
        with temporary_settings(
            {
                PREFECT_LOGGING_LEVEL: "CRITICAL",
                PREFECT_LOGGING_TO_API_ENABLED: False,
                PREFECT_LOGGING_LOG_PRINTS: False,
                PREFECT_SERVER_ALLOW_EPHEMERAL_MODE: False,
            }
        ):
            setup_logging(incremental=False)
            EventsWorker.set_client_override(NullEventsClient)
            yield
    finally:
        if previous_override is None:
            EventsWorker.set_client_override(None)
        else:
            client_type, client_kwargs = previous_override
            EventsWorker.set_client_override(client_type, **dict(client_kwargs))
        setup_logging(incremental=False)


def _run_without_prefect(
    flow_id: str,
    flow_fn: Callable[..., T],
    *args: object,
    orchestrator_backend: str = "none",
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
            orchestrator_backend=orchestrator_backend,
            started_at=started_at,
            finished_at=_now_utc(),
            error=str(exc),
        )
    return FlowRunResult.completed(
        flow_id=flow_id,
        flow_run_id=flow_run_id,
        orchestrator_backend=orchestrator_backend,
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
    except ImportError:
        return _run_without_prefect(flow_id, flow_fn, *args, orchestrator_backend="none", **kwargs)

    if not os.getenv("PREFECT_API_URL"):
        return _run_without_prefect(
            flow_id,
            flow_fn,
            *args,
            orchestrator_backend="prefect-local",
            **kwargs,
        )

    started_at = _now_utc()
    captured_flow_run_id = _generated_flow_run_id()

    @prefect_flow(name=flow_id, log_prints=False)
    def _prefect_wrapper() -> T:
        nonlocal captured_flow_run_id
        runtime_flow_run_id = getattr(prefect_flow_run, "id", None)
        if runtime_flow_run_id:
            captured_flow_run_id = str(runtime_flow_run_id)
        return flow_fn(*args, **kwargs)

    try:
        with _quiet_prefect_runtime():
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
