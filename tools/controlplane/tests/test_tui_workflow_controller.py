from __future__ import annotations

from collections.abc import Callable
import sys
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
    *,
    input_is_tty: bool | None = None,
) -> tuple[Any, MagicMock, MagicMock]:
    live = live_mock()
    listener = MagicMock()
    listener.input_is_tty = (
        bool(controller.console.is_terminal) if input_is_tty is None else input_is_tty
    )
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


def test_controller_live_footer_describes_ctrl_c_as_exit() -> None:
    console = MagicMock()
    console.is_terminal = False
    controller = TuiWorkflowController(console=console)
    captured: list[str] = []

    def capture_footer(dashboard: WorkflowDashboard, _sink: Any) -> None:
        captured.append(dashboard.footer_hint)

    run_with_mocks(controller, capture_footer)

    assert captured == ["l toggle logs | Ctrl+C exit"]


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


def test_controller_emits_action_exception_as_failed_event_through_sink() -> None:
    console = MagicMock()
    console.is_terminal = False
    controller = TuiWorkflowController(console=console)
    emitted = []

    def fail(_dashboard: WorkflowDashboard, sink: Any) -> None:
        original_emit = sink.emit

        def record(event: Any) -> None:
            emitted.append(event)
            original_emit(event)

        sink.emit = record
        raise RuntimeError("connection refused")

    with pytest.raises(RuntimeError, match="connection refused"):
        run_with_mocks(controller, fail)

    assert [event.kind for event in emitted] == ["task.failed"]
    assert emitted[0].detail == "connection refused"


def test_action_failure_uses_next_pending_phase_not_completed_phase() -> None:
    console = MagicMock()
    console.is_terminal = False
    controller = TuiWorkflowController(console=console)
    captured: list[WorkflowDashboard] = []

    def fail(dashboard: WorkflowDashboard, sink: Any) -> None:
        captured.append(dashboard)
        sink.emit(
            build_task_event(
                kind="task.completed",
                flow_id="test",
                task_id="first",
                title="Step one",
            )
        )
        raise RuntimeError("second phase failed")

    with pytest.raises(RuntimeError, match="second phase failed"):
        live = live_mock()
        listener = MagicMock()
        listener.input_is_tty = False
        with (
            patch("controlplane_tool.tui.workflow_controller.Live", return_value=live),
            patch(
                "controlplane_tool.tui.workflow_controller.WorkflowKeyListener",
                return_value=listener,
            ),
        ):
            controller.run_live_workflow(
                title="Test",
                summary_lines=[],
                planned_steps=["Step one", "Step two"],
                action=fail,
            )

    assert [step.state for step in captured[0].steps] == ["success", "failed"]


def test_action_failure_terminalizes_running_parent_and_nested_child() -> None:
    console = MagicMock()
    console.is_terminal = False
    controller = TuiWorkflowController(console=console)
    captured: list[WorkflowDashboard] = []

    def fail(dashboard: WorkflowDashboard, sink: Any) -> None:
        captured.append(dashboard)
        sink.emit(
            build_task_event(
                kind="task.running",
                flow_id="test",
                task_id="parent",
                title="Step one",
            )
        )
        sink.emit(
            build_task_event(
                kind="task.running",
                flow_id="test",
                task_id="child",
                parent_task_id="parent",
                title="Nested check",
            )
        )
        raise RuntimeError("nested failure")

    with pytest.raises(RuntimeError, match="nested failure"):
        run_with_mocks(controller, fail)

    assert captured[0].steps[0].state == "failed"
    assert captured[0].steps[0].children[0].state == "failed"


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


def test_controller_does_not_wait_when_output_is_tty_but_input_is_not() -> None:
    console = MagicMock()
    console.is_terminal = True
    controller = TuiWorkflowController(console=console)

    _, _, listener = run_with_mocks(
        controller,
        lambda _dashboard, _sink: None,
        input_is_tty=False,
    )

    listener.wait_for_acknowledgment.assert_not_called()


def test_key_listener_l_toggles_logs_and_refreshes() -> None:
    dashboard = MagicMock()
    refresh = MagicMock()
    listener = WorkflowKeyListener(dashboard, refresh)

    listener.handle_key("l")

    dashboard.toggle_logs.assert_called_once_with()
    refresh.assert_called_once_with()


def test_key_listener_reports_input_stream_tty_capability() -> None:
    input_stream = MagicMock()
    input_stream.isatty.return_value = False

    listener = WorkflowKeyListener(MagicMock(), MagicMock(), input_stream=input_stream)

    assert listener.input_is_tty is False


def test_key_listener_restores_terminal_and_joins_thread() -> None:
    input_stream = MagicMock()
    input_stream.isatty.return_value = True
    input_stream.fileno.return_value = 7
    termios = MagicMock()
    termios.TCSADRAIN = 9
    termios.tcgetattr.return_value = ["original"]
    tty = MagicMock()
    thread = MagicMock()

    with (
        patch.dict(sys.modules, {"termios": termios, "tty": tty}),
        patch("controlplane_tool.tui.workflow.Thread", return_value=thread),
    ):
        listener = WorkflowKeyListener(MagicMock(), MagicMock(), input_stream=input_stream)
        listener.start()
        listener.stop()

    tty.setcbreak.assert_called_once_with(7)
    thread.start.assert_called_once_with()
    thread.join.assert_called_once_with(timeout=0.3)
    termios.tcsetattr.assert_called_once_with(7, 9, ["original"])


def test_key_listener_can_restore_terminal_after_partial_start_failure() -> None:
    input_stream = MagicMock()
    input_stream.isatty.return_value = True
    input_stream.fileno.return_value = 7
    termios = MagicMock()
    termios.TCSADRAIN = 9
    termios.tcgetattr.return_value = ["original"]
    tty = MagicMock()
    tty.setcbreak.side_effect = RuntimeError("cbreak failed")

    with patch.dict(sys.modules, {"termios": termios, "tty": tty}):
        listener = WorkflowKeyListener(MagicMock(), MagicMock(), input_stream=input_stream)
        with pytest.raises(RuntimeError, match="cbreak failed"):
            listener.start()
        listener.stop()

    termios.tcsetattr.assert_called_once_with(7, 9, ["original"])


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


def test_controller_stops_listener_when_start_raises() -> None:
    console = MagicMock()
    console.is_terminal = False
    controller = TuiWorkflowController(console=console)
    live = live_mock()
    listener = MagicMock()
    listener.start.side_effect = RuntimeError("terminal setup failed")

    with (
        patch("controlplane_tool.tui.workflow_controller.Live", return_value=live),
        patch("controlplane_tool.tui.workflow_controller.WorkflowKeyListener", return_value=listener),
        pytest.raises(RuntimeError, match="terminal setup failed"),
    ):
        controller.run_live_workflow(
            title="Test",
            summary_lines=[],
            planned_steps=["Step one"],
            action=lambda _dashboard, _sink: None,
        )

    listener.stop.assert_called_once_with()


def test_listener_stop_failure_does_not_mask_action_failure() -> None:
    console = MagicMock()
    console.is_terminal = False
    controller = TuiWorkflowController(console=console)
    live = live_mock()
    listener = MagicMock()
    listener.input_is_tty = False
    listener.stop.side_effect = RuntimeError("restore failed")

    with (
        patch("controlplane_tool.tui.workflow_controller.Live", return_value=live),
        patch("controlplane_tool.tui.workflow_controller.WorkflowKeyListener", return_value=listener),
        pytest.raises(ValueError, match="action failed") as raised,
    ):
        controller.run_live_workflow(
            title="Test",
            summary_lines=[],
            planned_steps=["Step one"],
            action=lambda _dashboard, _sink: (_ for _ in ()).throw(ValueError("action failed")),
        )

    assert any("restore failed" in note for note in raised.value.__notes__)


def test_listener_stop_failure_is_raised_after_successful_action() -> None:
    console = MagicMock()
    console.is_terminal = False
    controller = TuiWorkflowController(console=console)
    live = live_mock()
    listener = MagicMock()
    listener.input_is_tty = False
    listener.stop.side_effect = RuntimeError("restore failed")

    with (
        patch("controlplane_tool.tui.workflow_controller.Live", return_value=live),
        patch("controlplane_tool.tui.workflow_controller.WorkflowKeyListener", return_value=listener),
        pytest.raises(RuntimeError, match="restore failed"),
    ):
        controller.run_live_workflow(
            title="Test",
            summary_lines=[],
            planned_steps=["Step one"],
            action=lambda _dashboard, _sink: "ok",
        )
