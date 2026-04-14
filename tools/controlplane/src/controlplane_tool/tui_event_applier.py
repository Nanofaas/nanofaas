"""
tui_event_applier.py — Maps workflow step events to WorkflowDashboard mutations.

Extracted from NanofaasTUI to isolate event→UI translation from menu routing.
"""
from __future__ import annotations

from typing import Any

from controlplane_tool.tui_workflow import WorkflowDashboard


class TuiEventApplier:
    """Translates ScenarioStepEvent / loadtest step events into dashboard mutations."""

    def apply_e2e_step_event(self, dashboard: WorkflowDashboard, event: Any) -> None:
        if event.status == "running":
            dashboard.mark_step_running(event.step_index)
            dashboard.append_log(f"[start] {event.step.summary}")
            return
        if event.status == "success":
            dashboard.mark_step_success(event.step_index)
            dashboard.append_log(f"[done] {event.step.summary}")
            return
        dashboard.mark_step_failed(event.step_index)
        dashboard.append_log(
            f"[fail] {event.step.summary}" + (f" ({event.error})" if event.error else "")
        )

    def apply_loadtest_step_event(self, dashboard: WorkflowDashboard, event: Any) -> None:
        step_index = dashboard.upsert_step(event.step_name)
        if event.status == "running":
            dashboard.mark_step_running(step_index)
            dashboard.append_log(f"[start] {event.step_name}")
            return
        if event.status == "passed":
            dashboard.mark_step_success(step_index)
            dashboard.append_log(
                f"[done] {event.step_name}" + (f" ({event.detail})" if event.detail else "")
            )
            return
        dashboard.mark_step_failed(step_index, event.detail)
        dashboard.append_log(
            f"[fail] {event.step_name}" + (f" ({event.detail})" if event.detail else "")
        )
