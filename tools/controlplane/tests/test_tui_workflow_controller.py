from __future__ import annotations

from collections.abc import Callable
from typing import Any
from unittest.mock import MagicMock, patch

import pytest

from controlplane_tool.tui.workflow import WorkflowDashboard, WorkflowKeyListener
from controlplane_tool.tui.workflow_controller import TuiWorkflowController
from workflow_tasks.workflow.event_builders import build_task_event


def live_mock() -> MagicMock:
    live = MagicMock()
    live.__enter__.return_value = live
    live.__exit__.return_value = False
    return live


def run_with_mocks(
    controller: TuiWorkflowController,
    action: Callable[[WorkflowDashboard, Any], Any],
) -> tuple[Any, MagicMock, MagicMock]:
    live = live_mock()
    listener = MagicMock()
    with (
        patch("controlplane_tool.tui.workflow_controller.Live", return_value=live) as live_type,
        patch("controlplane_tool.tui.workflow_controller.WorkflowKeyListener", return_value=listener),
    ):
        result = controller.run_live_workflow(
            title="Test",
            summary_lines=["Scenario: test"],
            planned_steps=["Step one"],
            action=action,
        )
    return result, live_type, listener


def test_controller_clears_console_uses_persistent_live_and_propagates_result() -> None:
    console = MagicMock()
    console.is_terminal = False
    controller = TuiWorkflowController(console=console)

    result, live_type, listener = run_with_mocks(controller, lambda _dashboard, _sink: 42)

    assert result == 42
    console.clear.assert_called_once_with()
    assert live_type.call_args.kwargs["transient"] is False
    listener.start.assert_called_once_with()
    listener.stop.assert_called_once_with()


def test_controller_turns_thrown_exception_into_failed_terminal_snapshot() -> None:
    console = MagicMock()
    console.is_terminal = False
    controller = TuiWorkflowController(console=console)
    captured: list[WorkflowDashboard] = []

    def fail(dashboard: WorkflowDashboard, _sink: Any) -> None:
        captured.append(dashboard)
        raise RuntimeError("connection refused")

    with pytest.raises(RuntimeError, match="connection refused"):
        run_with_mocks(controller, fail)

    assert captured[0].steps[0].state == "failed"
    assert "connection refused" in captured[0].steps[0].detail


def test_controller_displays_emitted_failure_event() -> None:
    console = MagicMock()
    console.is_terminal = False
    controller = TuiWorkflowController(console=console)
    captured: list[WorkflowDashboard] = []

    def emit_failure(dashboard: WorkflowDashboard, sink: Any) -> str:
        captured.append(dashboard)
        sink.emit(
            build_task_event(
                kind="task.failed",
                flow_id="test",
                task_id="step",
                title="Step one",
                detail="disk full",
            )
        )
        return "failed-result"

    result, _, _ = run_with_mocks(controller, emit_failure)

    assert result == "failed-result"
    assert captured[0].steps[0].state == "failed"
    assert captured[0].steps[0].detail == "disk full"


def test_controller_does_not_wait_for_acknowledgment_on_non_tty() -> None:
    console = MagicMock()
    console.is_terminal = False
    controller = TuiWorkflowController(console=console)

    _, _, listener = run_with_mocks(controller, lambda _dashboard, _sink: None)

    listener.wait_for_acknowledgment.assert_not_called()


def test_key_listener_l_toggles_logs_and_refreshes() -> None:
    dashboard = MagicMock()
    refresh = MagicMock()
    listener = WorkflowKeyListener(dashboard, refresh)

    listener.handle_key("l")

    dashboard.toggle_logs.assert_called_once_with()
    refresh.assert_called_once_with()


def test_controller_final_refresh_and_terminal_acknowledgment() -> None:
    console = MagicMock()
    console.is_terminal = True
    controller = TuiWorkflowController(console=console)

    _, live_type, listener = run_with_mocks(controller, lambda _dashboard, _sink: "ok")

    live = live_type.return_value
    assert live.update.call_args.kwargs["refresh"] is True
    listener.wait_for_acknowledgment.assert_called_once_with()


def test_controller_stops_listener_when_action_raises() -> None:
    console = MagicMock()
    console.is_terminal = False
    controller = TuiWorkflowController(console=console)

    def fail(_dashboard: WorkflowDashboard, _sink: Any) -> None:
        raise ValueError("boom")

    live = live_mock()
    listener = MagicMock()
    with (
        patch("controlplane_tool.tui.workflow_controller.Live", return_value=live),
        patch("controlplane_tool.tui.workflow_controller.WorkflowKeyListener", return_value=listener),
        pytest.raises(ValueError, match="boom"),
    ):
        controller.run_live_workflow(
            title="Test",
            summary_lines=[],
            planned_steps=["Step one"],
            action=fail,
        )

    listener.stop.assert_called_once_with()
