from unittest.mock import MagicMock
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
