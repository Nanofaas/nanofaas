from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar
from typing import Generator

from workflow_tasks.workflow.events import WorkflowContext, WorkflowSink

_workflow_sink_var: ContextVar[WorkflowSink | None] = ContextVar(
    "workflow_tasks_sink", default=None,
)
_workflow_sink_shared: WorkflowSink | None = None
_workflow_context_var: ContextVar[WorkflowContext | None] = ContextVar(
    "workflow_tasks_context", default=None,
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


def active_sink() -> WorkflowSink | None:
    return _workflow_sink_var.get() or _workflow_sink_shared


def get_workflow_context() -> WorkflowContext | None:
    return _workflow_context_var.get() or _workflow_context_shared


def has_workflow_sink() -> bool:
    return active_sink() is not None
