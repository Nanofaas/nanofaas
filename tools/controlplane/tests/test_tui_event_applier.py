from unittest.mock import MagicMock
from controlplane_tool.tui.event_applier import TuiEventApplier

def test_apply_e2e_step_event_running_marks_step_and_logs() -> None:
    dashboard = MagicMock()
    applier = TuiEventApplier()
    event = MagicMock()
    event.status = "running"
    event.step_index = 2
    event.step.summary = "deploy helm"
    event.error = None

    applier.apply_e2e_step_event(dashboard, event)

    dashboard.mark_step_running.assert_called_once_with(2)
    dashboard.append_log.assert_called_once_with("[start] deploy helm")


def test_apply_e2e_step_event_success_marks_step_success() -> None:
    dashboard = MagicMock()
    applier = TuiEventApplier()
    event = MagicMock()
    event.status = "success"
    event.step_index = 2
    event.step.summary = "deploy helm"
    event.error = None

    applier.apply_e2e_step_event(dashboard, event)

    dashboard.mark_step_success.assert_called_once_with(2)


def test_apply_e2e_step_event_failure_marks_step_failed() -> None:
    dashboard = MagicMock()
    applier = TuiEventApplier()
    event = MagicMock()
    event.status = "failed"
    event.step_index = 2
    event.step.summary = "deploy helm"
    event.error = "connection refused"

    applier.apply_e2e_step_event(dashboard, event)

    dashboard.mark_step_failed.assert_called_once_with(2)
    dashboard.append_log.assert_called_once_with("[fail] deploy helm (connection refused)")


def test_apply_loadtest_step_event_running_upserts_and_marks() -> None:
    dashboard = MagicMock()
    dashboard.upsert_step.return_value = 0
    applier = TuiEventApplier()
    event = MagicMock()
    event.status = "running"
    event.step_name = "load_k6"
    event.detail = ""

    applier.apply_loadtest_step_event(dashboard, event)

    dashboard.upsert_step.assert_called_once_with("load_k6")
    dashboard.mark_step_running.assert_called_once_with(0)
