from rich.console import Console

from controlplane_tool.tui_workflow import WorkflowDashboard


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

    dashboard.mark_step_running(1)
    dashboard.append_log("Bootstrapping VM")

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
