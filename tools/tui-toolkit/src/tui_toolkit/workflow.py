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


# ── Renderer ──────────────────────────────────────────────────────────────

from contextlib import contextmanager as _contextmanager  # noqa: E402

from rich.markup import escape  # noqa: E402
from rich.panel import Panel  # noqa: E402
from rich.rule import Rule  # noqa: E402
from rich.text import Text  # noqa: E402

import tui_toolkit.console as _console_mod  # noqa: E402
from tui_toolkit.context import get_ui  # noqa: E402


def _render_event(event: WorkflowEvent) -> None:
    """Render a WorkflowEvent to the active Rich console using the active theme."""
    theme = get_ui().theme
    _con = _console_mod.console

    if event.kind == "log.line":
        prefix = "stderr │ " if event.stream == "stderr" else ""
        _con.print(f"{prefix}{escape(event.line)}")
        return

    if event.kind == "phase.started":
        _con.print()
        _con.print(Rule(f"[{theme.accent_strong}]{escape(event.title)}[/]", style=theme.accent_dim))
        _con.print()
        return

    if event.kind == "task.running":
        if event.detail:
            _con.print(
                f"  [{theme.accent}]{theme.icon_running}[/] [bold]{escape(event.title)}[/]  "
                f"[{theme.muted}]{escape(event.detail)}[/]"
            )
        else:
            _con.print(f"  [{theme.accent}]{theme.icon_running}[/] [bold]{escape(event.title)}[/]")
        return

    if event.kind == "task.completed":
        body = f"[bold {theme.success}]{theme.icon_completed}  {escape(event.title)}[/]"
        if event.detail:
            body += f"\n\n[{theme.muted}]{escape(event.detail)}[/]"
        _con.print()
        _con.print(Panel(body, border_style=theme.success, padding=(0, 2)))
        _con.print()
        return

    if event.kind == "task.warning":
        _con.print(f"  [{theme.warning}]{theme.icon_warning}[/]  [{theme.warning}]{escape(event.title)}[/]")
        return

    if event.kind == "task.updated":
        if event.detail:
            _con.print(
                f"  [{theme.accent}]{theme.icon_updated}[/] [bold]{escape(event.title)}[/]  "
                f"[{theme.muted}]{escape(event.detail)}[/]"
            )
        else:
            _con.print(f"  [{theme.accent}]{theme.icon_updated}[/] [bold]{escape(event.title)}[/]")
        return

    if event.kind == "task.skipped":
        _con.print(f"  [{theme.muted}]{theme.icon_skipped}  {escape(event.title)}[/]")
        return

    if event.kind == "task.cancelled":
        body = f"[bold {theme.warning}]{theme.icon_cancelled}  {escape(event.title)}[/]"
        if event.detail:
            body += f"\n\n[{theme.muted}]{escape(event.detail)}[/]"
        _con.print()
        _con.print(Panel(body, border_style=theme.warning, padding=(0, 2)))
        _con.print()
        return

    if event.kind == "task.failed":
        body = f"[bold {theme.error}]{theme.icon_failed}  {escape(event.title)}[/]"
        if event.detail:
            body += f"\n\n[{theme.muted}]{escape(event.detail)}[/]"
        _con.print()
        _con.print(Panel(body, border_style=theme.error, padding=(0, 2)))
        _con.print()


def _emit(event: WorkflowEvent) -> None:
    sink = _active_sink()
    if sink is not None:
        sink.emit(event)
    else:
        _render_event(event)


# ── User-facing helpers ───────────────────────────────────────────────────


def header(subtitle: str | None = None) -> None:
    """Startup banner — uses the brand from the active UIContext."""
    ui = get_ui()
    _con = _console_mod.console
    if ui.brand.ascii_logo:
        _con.print()
        _con.print(Text(ui.brand.ascii_logo, style=ui.theme.brand, justify="center"))
    if subtitle:
        _con.print(
            Panel(
                f"[{ui.theme.muted}]{escape(subtitle)}[/]",
                border_style=ui.theme.accent_dim,
                padding=(0, 4),
            )
        )
    _con.print()


def phase(label: str) -> None:
    _emit(build_phase_event(label, context=get_workflow_context()))


def step(label: str, detail: str = "") -> None:
    _emit(build_task_event(
        kind="task.running", title=label, detail=detail,
        context=get_workflow_context(),
    ))


def success(label: str, detail: str = "") -> None:
    _emit(build_task_event(
        kind="task.completed", title=label, detail=detail,
        context=get_workflow_context(),
    ))


def warning(label: str) -> None:
    _emit(build_task_event(kind="task.warning", title=label, context=get_workflow_context()))


def skip(label: str) -> None:
    _emit(build_task_event(kind="task.skipped", title=label, context=get_workflow_context()))


def fail(label: str, detail: str = "") -> None:
    _emit(build_task_event(
        kind="task.failed", title=label, detail=detail,
        context=get_workflow_context(),
    ))


def workflow_log(message: str, *, stream: str = "stdout", context: WorkflowContext | None = None) -> None:
    _emit(build_log_event(line=message, stream=stream, context=context or get_workflow_context()))


def _child_context(*, task_id: str, parent_task_id: str | None, context: WorkflowContext | None) -> WorkflowContext:
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


@_contextmanager
def status(label: str):
    """Spinner context — routes to active sink's status if present, else
    falls back to Rich's console.status."""
    sink = _active_sink()
    if sink is not None:
        with sink.status(label):
            yield
        return
    with _console_mod.console.status(
        f"[{get_ui().theme.accent}]{escape(label)}…[/]", spinner="dots"
    ):
        yield


@_contextmanager
def workflow_step(
    *,
    task_id: str,
    title: str,
    parent_task_id: str | None = None,
    detail: str = "",
    context: WorkflowContext | None = None,
):
    """Emit running → completed (or failed) around a block of work."""
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
