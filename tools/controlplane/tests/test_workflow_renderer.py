from __future__ import annotations

from io import StringIO

import pytest
from rich.console import Console

from workflow_tasks.workflow.events import WorkflowEvent
from controlplane_tool.tui.workflow_renderer import RichWorkflowSink, render_event


@pytest.fixture(autouse=True)
def _mock_console(monkeypatch):
    import tui_toolkit.console as console_mod
    mock_console = Console(file=StringIO(), highlight=False)
    monkeypatch.setattr(console_mod, "console", mock_console)
    return mock_console


def test_render_log_line_does_not_crash() -> None:
    event = WorkflowEvent(kind="log.line", flow_id="f", line="hello world")
    render_event(event)


def test_render_phase_started_does_not_crash() -> None:
    event = WorkflowEvent(kind="phase.started", flow_id="f", title="Provisioning")
    render_event(event)


def test_render_task_running_does_not_crash() -> None:
    event = WorkflowEvent(kind="task.running", flow_id="f", title="Deploy VM")
    render_event(event)


def test_render_task_running_with_detail_does_not_crash() -> None:
    event = WorkflowEvent(kind="task.running", flow_id="f", title="Deploy VM", detail="step 2/3")
    render_event(event)


def test_render_task_completed_does_not_crash() -> None:
    event = WorkflowEvent(kind="task.completed", flow_id="f", title="Deploy VM")
    render_event(event)


def test_render_task_failed_does_not_crash() -> None:
    event = WorkflowEvent(kind="task.failed", flow_id="f", title="Deploy VM", detail="timeout")
    render_event(event)


def test_render_task_skipped_does_not_crash() -> None:
    event = WorkflowEvent(kind="task.skipped", flow_id="f", title="Optional step")
    render_event(event)


def test_render_task_warning_does_not_crash() -> None:
    event = WorkflowEvent(kind="task.warning", flow_id="f", title="Low disk space")
    render_event(event)


def test_render_task_cancelled_does_not_crash() -> None:
    event = WorkflowEvent(kind="task.cancelled", flow_id="f", title="Deploy VM")
    render_event(event)


def test_render_task_updated_does_not_crash() -> None:
    event = WorkflowEvent(kind="task.updated", flow_id="f", title="Deploy VM", detail="step 2/3")
    render_event(event)


def test_rich_workflow_sink_emit_does_not_crash() -> None:
    sink = RichWorkflowSink()
    sink.emit(WorkflowEvent(kind="task.running", flow_id="f", title="Test"))


def test_rich_workflow_sink_status_does_not_crash() -> None:
    sink = RichWorkflowSink()
    with sink.status("loading"):
        pass
