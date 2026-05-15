from __future__ import annotations

from workflow_tasks.integrations.prefect import PrefectEventBridge, normalize_task_state
from workflow_tasks.workflow.events import WorkflowContext


def test_normalize_completed_state() -> None:
    event = normalize_task_state(flow_id="e2e.k8s_vm", task_id="vm.ensure_running", state_name="Completed")
    assert event.kind == "task.completed"
    assert event.task_id == "vm.ensure_running"


def test_normalize_failed_state() -> None:
    event = normalize_task_state(flow_id="e2e", task_id="x", state_name="Failed")
    assert event.kind == "task.failed"


def test_normalize_crashed_maps_to_failed() -> None:
    event = normalize_task_state(flow_id="e2e", task_id="x", state_name="Crashed")
    assert event.kind == "task.failed"


def test_normalize_unknown_state_maps_to_updated() -> None:
    event = normalize_task_state(flow_id="e2e", task_id="x", state_name="SomeUnknownState")
    assert event.kind == "task.updated"


def test_normalize_preserves_parent_task_id_from_args() -> None:
    event = normalize_task_state(
        flow_id="e2e", task_id="vm.up", parent_task_id="tests.run_checks", state_name="Completed",
    )
    assert event.parent_task_id == "tests.run_checks"


def test_normalize_preserves_parent_task_id_from_context() -> None:
    event = normalize_task_state(
        flow_id="e2e", task_id="vm.up", state_name="Completed",
        context=WorkflowContext(flow_id="e2e", task_id="vm.up", parent_task_id="tests.run_checks"),
    )
    assert event.parent_task_id == "tests.run_checks"


def test_prefect_event_bridge_emits_state_event() -> None:
    emitted = []
    bridge = PrefectEventBridge(emit=emitted.append)
    event = bridge.emit_task_state(flow_id="e2e", task_id="vm.up", state_name="Completed")
    assert emitted == [event]
    assert event.kind == "task.completed"


def test_prefect_event_bridge_emits_log_event() -> None:
    emitted = []
    bridge = PrefectEventBridge(emit=emitted.append)
    event = bridge.emit_log(flow_id="e2e", line="docker push ok")
    assert emitted == [event]
    assert event.kind == "log.line"
    assert event.line == "docker push ok"


def test_prefect_event_bridge_noop_without_emit_callback() -> None:
    bridge = PrefectEventBridge()
    event = bridge.emit_task_state(flow_id="e2e", task_id="x", state_name="Completed")
    assert event.kind == "task.completed"
