from __future__ import annotations

from workflow_tasks.integrations.prefect import PrefectEventBridge


def test_prefect_event_bridge_emits_normalized_task_and_log_events() -> None:
    captured = []
    bridge = PrefectEventBridge(captured.append)

    bridge.emit_task_state(
        flow_id="e2e.k8s_vm",
        task_id="vm.ensure_running",
        task_run_id="task-run-1",
        state_name="Running",
    )
    bridge.emit_log(
        flow_id="e2e.k8s_vm",
        task_id="vm.ensure_running",
        task_run_id="task-run-1",
        line="vm boot ok",
    )

    assert [event.kind for event in captured] == ["task.running", "log.line"]
    assert captured[0].task_run_id == "task-run-1"
    assert captured[1].line == "vm boot ok"
