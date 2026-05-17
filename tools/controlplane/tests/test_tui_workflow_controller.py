from unittest.mock import MagicMock, patch
import pytest
from controlplane_tool.tui.workflow_controller import TuiWorkflowController


def test_raise_on_nonzero_command_result_raises_for_nonzero() -> None:
    controller = TuiWorkflowController(event_applier=MagicMock())
    result = MagicMock()
    result.return_code = 1
    result.stderr = "something failed"
    result.stdout = ""

    with pytest.raises(RuntimeError, match="something failed"):
        controller._raise_on_nonzero_command_result(result)


def test_raise_on_nonzero_command_result_does_not_raise_for_zero() -> None:
    controller = TuiWorkflowController(event_applier=MagicMock())
    result = MagicMock()
    result.return_code = 0

    controller._raise_on_nonzero_command_result(result)  # no exception


def test_raise_on_nonzero_recurses_over_list() -> None:
    controller = TuiWorkflowController(event_applier=MagicMock())
    ok = MagicMock()
    ok.return_code = 0
    bad = MagicMock()
    bad.return_code = 2
    bad.stderr = "disk full"
    bad.stdout = ""

    with pytest.raises(RuntimeError, match="disk full"):
        controller._raise_on_nonzero_command_result([ok, bad])


def test_run_live_workflow_calls_fail_when_action_raises() -> None:
    """When action raises, fail() must be called with the error while the sink is still active."""
    from controlplane_tool.tui.workflow import WorkflowDashboard

    controller = TuiWorkflowController(event_applier=MagicMock())

    def failing_action(dashboard: WorkflowDashboard, sink) -> None:
        raise RuntimeError("step 30 failed: connection refused")

    mock_live = MagicMock()
    mock_live.__enter__ = MagicMock(return_value=mock_live)
    mock_live.__exit__ = MagicMock(return_value=False)

    with patch("rich.live.Live", return_value=mock_live), \
         patch("controlplane_tool.tui.workflow.WorkflowKeyListener"), \
         patch("controlplane_tool.tui.workflow_controller._fail") as mock_fail:

        with pytest.raises(RuntimeError, match="connection refused"):
            controller.run_live_workflow(
                title="Test",
                summary_lines=["Test scenario"],
                planned_steps=["step one", "step two"],
                action=failing_action,
            )

    mock_fail.assert_called_once()
    call_args = mock_fail.call_args
    assert "connection refused" in call_args.args[0]
    assert "detail" in call_args.kwargs
    assert call_args.kwargs["detail"]
