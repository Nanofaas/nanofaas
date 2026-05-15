from __future__ import annotations

from workflow_tasks.tasks.models import CommandTaskSpec, TaskResult
from controlplane_tool.tui.event_aggregator import WorkflowEventAggregator
from controlplane_tool.workflow.task_events import task_result_event, task_started_event


def test_task_started_event_uses_task_id_summary_and_running_kind() -> None:
    task = CommandTaskSpec(task_id="x", summary="Run X", argv=("echo", "x"))

    event = task_started_event(task, flow_id="tasks.test")

    assert event.flow_id == "tasks.test"
    assert event.task_id == "x"
    assert event.title == "Run X"
    assert event.kind == "task.running"


def test_task_result_event_maps_failed_status_to_detail() -> None:
    task = CommandTaskSpec(task_id="x", summary="Run X", argv=("false",))
    result = TaskResult(task_id="x", status="failed", return_code=1, stderr="failed")

    event = task_result_event(task, result, flow_id="tasks.test")

    assert event.flow_id == "tasks.test"
    assert event.task_id == "x"
    assert event.kind == "task.failed"
    assert event.detail == "failed"


def test_task_result_event_maps_skipped_status_to_terminal_tui_event() -> None:
    bridge = WorkflowEventAggregator()
    task = CommandTaskSpec(task_id="x", summary="Run X", argv=("echo", "x"))
    result = TaskResult(task_id="x", status="skipped")

    bridge.handle_event(task_started_event(task, flow_id="tasks.test"))
    bridge.handle_event(task_result_event(task, result, flow_id="tasks.test"))

    snapshot = bridge.snapshot()
    assert snapshot.phases[0].status == "success"
    assert snapshot.phases[0].detail == "skipped"
