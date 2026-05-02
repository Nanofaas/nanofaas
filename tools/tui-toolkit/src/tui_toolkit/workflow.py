"""Workflow event builders, sink/context binding, and renderer.

Built in two passes:
  - Task 1.7: event builders + bind_workflow_sink/context + get_workflow_context
  - Task 1.8: header/phase/step/success/warning/skip/fail/status/workflow_step
              + _render_event
"""
from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar
from typing import Generator

from tui_toolkit.events import WorkflowContext, WorkflowEvent, WorkflowSink


# ── Active sink + context plumbing ────────────────────────────────────────

_workflow_sink_var: ContextVar["WorkflowSink | None"] = ContextVar(
    "tui_toolkit_workflow_sink", default=None,
)
_workflow_sink_shared: "WorkflowSink | None" = None
_workflow_context_var: ContextVar[WorkflowContext | None] = ContextVar(
    "tui_toolkit_workflow_context", default=None,
)
_workflow_context_shared: WorkflowContext | None = None


@contextmanager
def bind_workflow_sink(sink: WorkflowSink) -> Generator[None, None, None]:
    global _workflow_sink_shared
    previous = _workflow_sink_shared
    _workflow_sink_shared = sink
    token = _workflow_sink_var.set(sink)
    try:
        yield
    finally:
        _workflow_sink_var.reset(token)
        _workflow_sink_shared = previous


@contextmanager
def bind_workflow_context(context: WorkflowContext) -> Generator[None, None, None]:
    global _workflow_context_shared
    previous = _workflow_context_shared
    _workflow_context_shared = context
    token = _workflow_context_var.set(context)
    try:
        yield
    finally:
        _workflow_context_var.reset(token)
        _workflow_context_shared = previous


def _active_sink() -> "WorkflowSink | None":
    return _workflow_sink_var.get() or _workflow_sink_shared


def get_workflow_context() -> WorkflowContext | None:
    return _workflow_context_var.get() or _workflow_context_shared


def has_workflow_sink() -> bool:
    return _active_sink() is not None


# ── Event builders ────────────────────────────────────────────────────────


def _resolve_context_fields(
    *,
    flow_id: str | None,
    flow_run_id: str | None,
    task_id: str | None,
    parent_task_id: str | None,
    task_run_id: str | None,
    context: WorkflowContext | None,
    inherit_task_id: bool = True,
) -> tuple[str, str | None, str | None, str | None, str | None]:
    active = context or WorkflowContext()
    resolved_task_id = task_id if task_id is not None else (active.task_id if inherit_task_id else None)
    resolved_parent = parent_task_id if parent_task_id is not None else active.parent_task_id
    return (
        flow_id or active.flow_id,
        flow_run_id or active.flow_run_id,
        resolved_task_id,
        resolved_parent,
        task_run_id or active.task_run_id,
    )


def build_task_event(
    *,
    kind: str,
    flow_id: str | None = None,
    flow_run_id: str | None = None,
    task_id: str | None = None,
    parent_task_id: str | None = None,
    task_run_id: str | None = None,
    title: str = "",
    detail: str = "",
    context: WorkflowContext | None = None,
) -> WorkflowEvent:
    resolved = _resolve_context_fields(
        flow_id=flow_id, flow_run_id=flow_run_id, task_id=task_id,
        parent_task_id=parent_task_id, task_run_id=task_run_id,
        context=context,
    )
    return WorkflowEvent(
        kind=kind,
        flow_id=resolved[0], flow_run_id=resolved[1], task_id=resolved[2],
        parent_task_id=resolved[3], task_run_id=resolved[4],
        title=title or resolved[2] or kind,
        detail=detail,
    )


def build_phase_event(
    label: str,
    *,
    flow_id: str | None = None,
    flow_run_id: str | None = None,
    context: WorkflowContext | None = None,
) -> WorkflowEvent:
    resolved = _resolve_context_fields(
        flow_id=flow_id, flow_run_id=flow_run_id, task_id=None,
        parent_task_id=None, task_run_id=None, context=context,
    )
    return WorkflowEvent(
        kind="phase.started",
        flow_id=resolved[0], flow_run_id=resolved[1], task_id=resolved[2],
        parent_task_id=resolved[3], task_run_id=resolved[4],
        title=label,
    )


def build_log_event(
    *,
    line: str,
    flow_id: str | None = None,
    flow_run_id: str | None = None,
    task_id: str | None = None,
    parent_task_id: str | None = None,
    task_run_id: str | None = None,
    stream: str = "stdout",
    context: WorkflowContext | None = None,
) -> WorkflowEvent:
    resolved = _resolve_context_fields(
        flow_id=flow_id, flow_run_id=flow_run_id, task_id=task_id,
        parent_task_id=parent_task_id, task_run_id=task_run_id,
        context=context,
    )
    return WorkflowEvent(
        kind="log.line",
        flow_id=resolved[0], flow_run_id=resolved[1], task_id=resolved[2],
        parent_task_id=resolved[3], task_run_id=resolved[4],
        stream=stream, line=line,
    )
