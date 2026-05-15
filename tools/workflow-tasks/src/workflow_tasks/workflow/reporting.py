from __future__ import annotations

from contextlib import contextmanager
from typing import Generator

from workflow_tasks.workflow.context import (
    active_sink,
    bind_workflow_context,
    get_workflow_context,
)
from workflow_tasks.workflow.event_builders import build_log_event, build_phase_event, build_task_event
from workflow_tasks.workflow.events import WorkflowContext, WorkflowEvent


def _emit(event: WorkflowEvent) -> None:
    sink = active_sink()
    if sink is not None:
        sink.emit(event)


def phase(label: str) -> None:
    _emit(build_phase_event(label, context=get_workflow_context()))


def step(label: str, detail: str = "") -> None:
    _emit(build_task_event(kind="task.running", title=label, detail=detail, context=get_workflow_context()))


def success(label: str, detail: str = "") -> None:
    _emit(build_task_event(kind="task.completed", title=label, detail=detail, context=get_workflow_context()))


def warning(label: str) -> None:
    _emit(build_task_event(kind="task.warning", title=label, context=get_workflow_context()))


def skip(label: str) -> None:
    _emit(build_task_event(kind="task.skipped", title=label, context=get_workflow_context()))


def fail(label: str, detail: str = "") -> None:
    _emit(build_task_event(kind="task.failed", title=label, detail=detail, context=get_workflow_context()))


def workflow_log(message: str, *, stream: str = "stdout", context: WorkflowContext | None = None) -> None:
    _emit(build_log_event(line=message, stream=stream, context=context or get_workflow_context()))


@contextmanager
def status(label: str) -> Generator[None, None, None]:
    sink = active_sink()
    if sink is not None:
        with sink.status(label):
            yield
    else:
        yield


def _child_context(
    *, task_id: str, parent_task_id: str | None, context: WorkflowContext | None
) -> WorkflowContext:
    active = context or get_workflow_context() or WorkflowContext()
    resolved_parent = parent_task_id
    if resolved_parent is None:
        resolved_parent = active.task_id or active.parent_task_id
    return WorkflowContext(
        flow_id=active.flow_id,
        flow_run_id=active.flow_run_id,
        task_id=task_id,
        parent_task_id=resolved_parent,
        task_run_id=active.task_run_id,
    )


@contextmanager
def workflow_step(
    *,
    task_id: str,
    title: str,
    parent_task_id: str | None = None,
    detail: str = "",
    context: WorkflowContext | None = None,
) -> Generator[WorkflowContext, None, None]:
    child = _child_context(task_id=task_id, parent_task_id=parent_task_id, context=context)
    _emit(build_task_event(
        kind="task.running", task_id=task_id, parent_task_id=child.parent_task_id,
        title=title, detail=detail, context=child,
    ))
    with bind_workflow_context(child):
        try:
            yield child
        except Exception as exc:
            _emit(build_task_event(
                kind="task.failed", task_id=task_id, parent_task_id=child.parent_task_id,
                title=title, detail=detail or str(exc), context=child,
            ))
            raise
        else:
            _emit(build_task_event(
                kind="task.completed", task_id=task_id, parent_task_id=child.parent_task_id,
                title=title, detail=detail, context=child,
            ))
