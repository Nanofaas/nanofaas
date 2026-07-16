from __future__ import annotations

from rich.console import Console

from controlplane_tool.tui.event_aggregator import WorkflowEventAggregator
from controlplane_tool.tui.workflow import TuiWorkflowSink, WorkflowDashboard, WorkflowStepState
from workflow_tasks.workflow.event_builders import build_log_event, build_task_event


def rendered_text(dashboard: WorkflowDashboard, *, width: int = 120) -> str:
    console = Console(record=True, width=width)
    console.print(dashboard.render())
    return console.export_text()


def test_dashboard_renders_summary_phases_logs_breadcrumb_and_footer() -> None:
    dashboard = WorkflowDashboard(
        title="E2E Scenarios",
        breadcrumb="Main / Validation",
        footer_hint="l toggle logs | Ctrl+C back",
        summary_lines=["Scenario: k3s-junit-curl"],
        planned_steps=["Ensure VM is running"],
    )
    dashboard.apply_event(
        build_task_event(
            kind="task.running",
            flow_id="e2e",
            task_id="vm.ensure",
            title="Ensure VM is running",
        )
    )
    dashboard.apply_event(build_log_event(flow_id="e2e", task_id="vm.ensure", line="Boot VM"))

    text = rendered_text(dashboard)

    assert "E2E Scenarios" in text
    assert "Scenario: k3s-junit-curl" in text
    assert "Execution Phases" in text
    assert "Raw Command Output" in text
    assert "Main / Validation" in text
    assert "l toggle logs" in text
    assert "Boot VM" in text


def test_dashboard_default_footer_describes_ctrl_c_as_exit() -> None:
    dashboard = WorkflowDashboard(title="E2E")

    assert dashboard.footer_hint == "l toggle logs | Ctrl+C exit"


def test_dashboard_renders_nested_child_without_replacing_planned_rows() -> None:
    dashboard = WorkflowDashboard(
        title="E2E",
        planned_steps=["Run verification", "Teardown VM"],
    )
    dashboard.apply_event(
        build_task_event(
            kind="task.running", flow_id="e2e", task_id="verify", title="Run verification"
        )
    )
    dashboard.apply_event(
        build_task_event(
            kind="task.completed",
            flow_id="e2e",
            task_id="health",
            parent_task_id="verify",
            title="Verify",
            detail="control-plane health",
        )
    )

    assert [step.label for step in dashboard.steps] == ["Run verification", "Teardown VM"]
    assert dashboard.steps[0].children[0].state == "success"
    assert "Nested Verification Work" in rendered_text(dashboard)
    assert "Verify control-plane health" in rendered_text(dashboard)


def test_dashboard_toggle_hides_and_restores_log_panel() -> None:
    dashboard = WorkflowDashboard(title="E2E", planned_steps=["Build"])
    dashboard.append_log("docker build")

    dashboard.toggle_logs()
    assert "Raw Command Output" not in rendered_text(dashboard)

    dashboard.toggle_logs()
    assert "Raw Command Output" in rendered_text(dashboard)


def test_dashboard_tracks_cancelled_and_updated_states() -> None:
    dashboard = WorkflowDashboard(title="E2E")
    dashboard.apply_event(
        build_task_event(
            kind="task.updated",
            flow_id="e2e",
            task_id="images",
            title="Build images",
            detail="50%",
        )
    )
    assert dashboard.steps[0].state == "running"
    assert dashboard.steps[0].detail == "50%"

    dashboard.apply_event(
        build_task_event(
            kind="task.cancelled",
            flow_id="e2e",
            task_id="images",
            title="Build images",
            detail="cancelled by user",
        )
    )
    assert dashboard.steps[0].state == "cancelled"
    assert "Build images" in rendered_text(dashboard)


def test_dashboard_renders_durations_right_aligned_and_panels_bottom_aligned() -> None:
    dashboard = WorkflowDashboard(title="E2E", summary_lines=["Scenario: cli-stack"])
    dashboard.steps = [
        WorkflowStepState(label="Short task", state="success", started_at=10.0, finished_at=11.0),
        WorkflowStepState(label="Longer task", state="success", started_at=20.0, finished_at=32.3),
    ]
    dashboard.log_lines = ["one line"]

    text = rendered_text(dashboard, width=100)
    short_line = next(line for line in text.splitlines() if "Short task" in line)
    long_line = next(line for line in text.splitlines() if "Longer task" in line)

    assert short_line.split("││", 1)[0].rstrip().endswith("1.0s")
    assert long_line.split("││", 1)[0].rstrip().endswith("12.3s")
    assert any(line.count("╯") == 2 for line in text.splitlines())


def test_dashboard_syncs_from_snapshot_without_aliasing() -> None:
    aggregator = WorkflowEventAggregator()
    sink = TuiWorkflowSink(aggregator)
    sink.emit(
        build_task_event(
            kind="task.running", flow_id="e2e", task_id="build", title="Build images"
        )
    )
    sink.emit(build_log_event(flow_id="e2e", task_id="build", line="docker push ok"))

    dashboard = WorkflowDashboard(title="E2E")
    dashboard.sync_from_snapshot(aggregator.snapshot())

    assert dashboard.steps[0].label == "Build images"
    assert dashboard.log_lines == ["[step] Build images", "docker push ok"]
    dashboard.steps[0].label = "changed"
    assert aggregator.snapshot().phases[0].label == "Build images"
