from __future__ import annotations

from collections.abc import Callable

from controlplane_tool.workflow_events import build_log_event, normalize_task_state
from controlplane_tool.workflow_models import WorkflowEvent


class PrefectEventBridge:
    def __init__(self, emit: Callable[[WorkflowEvent], None] | None = None) -> None:
        self._emit = emit or (lambda event: None)

    def emit_task_state(
        self,
        *,
        flow_id: str,
        task_id: str,
        state_name: str,
        flow_run_id: str | None = None,
        task_run_id: str | None = None,
        title: str | None = None,
        detail: str = "",
    ) -> WorkflowEvent:
        event = normalize_task_state(
            flow_id=flow_id,
            flow_run_id=flow_run_id,
            task_id=task_id,
            task_run_id=task_run_id,
            state_name=state_name,
            title=title,
            detail=detail,
        )
        self._emit(event)
        return event

    def emit_log(
        self,
        *,
        flow_id: str,
        line: str,
        flow_run_id: str | None = None,
        task_id: str | None = None,
        task_run_id: str | None = None,
        stream: str = "stdout",
    ) -> WorkflowEvent:
        event = build_log_event(
            flow_id=flow_id,
            flow_run_id=flow_run_id,
            task_id=task_id,
            task_run_id=task_run_id,
            stream=stream,
            line=line,
        )
        self._emit(event)
        return event
