from rich.console import Console

from controlplane_tool.tui_workflow import WorkflowDashboard
from controlplane_tool.workflow_events import build_log_event, build_phase_event, build_task_event


def test_workflow_dashboard_renders_log_and_phase_panels() -> None:
    dashboard = WorkflowDashboard(
        title="E2E Scenarios",
        summary_lines=[
            "Scenario: k8s-vm",
            "VM Name: nanofaas-e2e",
            "Runtime: java",
        ],
        planned_steps=[
            "Ensure VM is running",
            "Sync project to VM",
            "Run K8sE2eTest in VM",
        ],
    )

    dashboard.apply_event(build_phase_event("Ensure VM is running", flow_id="e2e.k8s_vm"))
    dashboard.apply_event(
        build_task_event(
            kind="task.running",
            flow_id="e2e.k8s_vm",
            task_id="vm.ensure_running",
            title="Ensure VM is running",
        )
    )
    dashboard.apply_event(
        build_log_event(
            flow_id="e2e.k8s_vm",
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
        summary_lines=["Scenario: k8s-vm"],
        planned_steps=["Ensure VM is running"],
    )
    dashboard.append_log("Bootstrapping VM")
    dashboard.show_logs = False

    console = Console(record=True, width=140)
    console.print(dashboard.render())
    text = console.export_text()

    assert "Execution Log" not in text
    assert "Execution Phases" in text
