from rich.console import Console

from controlplane_tool.tui_prefect_bridge import TuiPrefectBridge
from controlplane_tool.tui_workflow import WorkflowDashboard, WorkflowStepState
from controlplane_tool.workflow_events import (
    build_log_event,
    build_phase_event,
    build_task_event,
    normalize_task_state,
)


def test_workflow_dashboard_renders_log_and_phase_panels() -> None:
    dashboard = WorkflowDashboard(
        title="E2E Scenarios",
        summary_lines=[
            "Scenario: k3s-junit-curl",
            "VM Name: nanofaas-e2e",
            "Runtime: java",
        ],
        planned_steps=[
            "Ensure VM is running",
            "Sync project to VM",
            "Run K8sE2eTest in VM",
        ],
    )

    dashboard.apply_event(build_phase_event("Ensure VM is running", flow_id="e2e.k3s_junit_curl"))
    dashboard.apply_event(
        build_task_event(
            kind="task.running",
            flow_id="e2e.k3s_junit_curl",
            task_id="vm.ensure_running",
            title="Ensure VM is running",
        )
    )
    dashboard.apply_event(
        build_log_event(
            flow_id="e2e.k3s_junit_curl",
            task_id="vm.ensure_running",
            task_run_id="task-run-1",
            line="Bootstrapping VM",
        )
    )

    console = Console(record=True, width=140)
    console.print(dashboard.render())
    text = console.export_text()

    assert "Execution Log" in text
    assert "Execution Phases" in text
    assert "Ensure VM is running" in text
    assert "Bootstrapping VM" in text


def test_workflow_dashboard_can_hide_log_panel() -> None:
    dashboard = WorkflowDashboard(
        title="E2E Scenarios",
        summary_lines=["Scenario: k3s-junit-curl"],
        planned_steps=["Ensure VM is running"],
    )
    dashboard.append_log("Bootstrapping VM")
    dashboard.show_logs = False

    console = Console(record=True, width=140)
    console.print(dashboard.render())
    text = console.export_text()

    assert "Execution Log" not in text
    assert "Execution Phases" in text


def test_workflow_dashboard_marks_cancelled_task_visible() -> None:
    dashboard = WorkflowDashboard(
        title="E2E Scenarios",
        summary_lines=["Scenario: k3s-junit-curl"],
    )

    dashboard.apply_event(
        normalize_task_state(
            flow_id="e2e.k3s_junit_curl",
            task_id="vm.ensure_running",
            state_name="Cancelled",
            title="Ensure VM is running",
        )
    )

    console = Console(record=True, width=140)
    console.print(dashboard.render())
    text = console.export_text()

    assert "Ensure VM is running" in text
    assert dashboard.steps[0].state == "cancelled"


def test_workflow_dashboard_tracks_task_updated_detail() -> None:
    dashboard = WorkflowDashboard(
        title="E2E Scenarios",
        summary_lines=["Scenario: k3s-junit-curl"],
    )

    dashboard.apply_event(
        build_task_event(
            kind="task.updated",
            flow_id="e2e.k3s_junit_curl",
            task_id="images.build_core",
            title="Build core images",
            detail="50%",
        )
    )

    assert dashboard.steps[0].label == "Build core images"
    assert dashboard.steps[0].detail == "50%"


def test_workflow_dashboard_renders_bridge_snapshot_with_cancelled_task_and_logs() -> None:
    bridge = TuiPrefectBridge()
    bridge.handle_event(
        build_task_event(
            kind="task.updated",
            flow_id="e2e.k3s_junit_curl",
            task_id="images.build_core",
            title="Build core images",
            detail="building",
        )
    )
    bridge.handle_event(
        build_log_event(
            flow_id="e2e.k3s_junit_curl",
            task_id="images.build_core",
            line="docker push ok",
        )
    )
    bridge.handle_event(
        build_task_event(
            kind="task.cancelled",
            flow_id="e2e.k3s_junit_curl",
            task_id="images.build_core",
            title="Build core images",
            detail="cancelled by user",
        )
    )

    dashboard = WorkflowDashboard(
        title="E2E Scenarios",
        summary_lines=["Scenario: k3s-junit-curl"],
    )
    dashboard.sync_from_snapshot(bridge.snapshot())

    console = Console(record=True, width=140)
    console.print(dashboard.render())
    text = console.export_text()

    assert "Build core images" in text
    assert "docker push ok" in text
    assert dashboard.steps[0].state == "cancelled"


def test_workflow_dashboard_mark_step_running_advances_single_active_step() -> None:
    dashboard = WorkflowDashboard(
        title="E2E Scenarios",
        summary_lines=["Scenario: k3s-junit-curl"],
        planned_steps=["Ensure VM is running", "Build core images"],
    )

    dashboard.mark_step_running(1)
    dashboard.mark_step_running(2)

    assert dashboard.steps[0].state == "success"
    assert dashboard.steps[1].state == "running"


def test_workflow_dashboard_renders_step_durations_right_aligned() -> None:
    dashboard = WorkflowDashboard(
        title="E2E Scenarios",
        summary_lines=["Scenario: cli-stack"],
    )
    dashboard.steps = [
        WorkflowStepState(
            label="Short task",
            state="success",
            started_at=10.0,
            finished_at=11.0,
        ),
        WorkflowStepState(
            label="Longer task",
            state="success",
            started_at=20.0,
            finished_at=32.3,
        ),
    ]

    console = Console(record=True, width=100)
    console.print(dashboard.render())
    text = console.export_text()
    short_line = next(line for line in text.splitlines() if "Short task" in line)
    long_line = next(line for line in text.splitlines() if "Longer task" in line)
    short_phase_line = short_line.split("││", 1)[0]
    long_phase_line = long_line.split("││", 1)[0]

    assert "1.0s" in short_line
    assert "12.3s" in long_line
    assert short_phase_line.rstrip().endswith("1.0s")
    assert long_phase_line.rstrip().endswith("12.3s")


def test_workflow_dashboard_keeps_log_panel_bottom_aligned_with_phases() -> None:
    dashboard = WorkflowDashboard(
        title="E2E Scenarios",
        summary_lines=["Scenario: cli-stack", "Runner: cli-stack"],
    )
    dashboard.steps = [
        WorkflowStepState(label="Step 1", state="success", started_at=0.0, finished_at=1.0),
        WorkflowStepState(label="Step 2", state="pending"),
    ]
    dashboard.log_lines = ["only one line"]

    console = Console(record=True, width=100)
    console.print(dashboard.render())
    text = console.export_text()
    final_line = [line for line in text.splitlines() if line.strip()][-1]

    assert final_line.count("╯") == 2


def test_teardown_row_stays_pending_until_parent_cleanup_step_completes() -> None:
    dashboard = WorkflowDashboard(
        title="E2E Scenarios",
        summary_lines=["Scenario: k3s-junit-curl"],
        planned_steps=[
            "Ensure VM is running",
            "Provision base VM dependencies",
            "Sync project to VM",
            "Run k3s-junit-curl verification",
            "Uninstall function-runtime Helm release",
            "Uninstall control-plane Helm release",
            "Delete E2E namespace",
            "Teardown VM",
        ],
    )

    dashboard.apply_event(
        build_task_event(
            kind="task.running",
            flow_id="e2e.k3s_junit_curl",
            task_id="tests.run_k3s_curl_checks",
            title="Run k3s-junit-curl verification",
        )
    )
    dashboard.apply_event(
        build_task_event(
            kind="task.completed",
            flow_id="e2e.k3s_junit_curl",
            task_id="vm.teardown",
            title="Teardown VM",
        )
    )

    assert dashboard.steps[-1].label == "Teardown VM"
    assert dashboard.steps[-1].state == "pending"
