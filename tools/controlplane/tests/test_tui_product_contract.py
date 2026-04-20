from rich.console import Console

from controlplane_tool.tui_app import NanofaasTUI
from controlplane_tool.tui_workflow import WorkflowDashboard


def _render_dashboard_text(dashboard: WorkflowDashboard, *, width: int = 140) -> str:
    console = Console(record=True, width=width)
    console.print(dashboard.render())
    return console.export_text()


def test_tui_main_menu_uses_canonical_product_sections() -> None:
    assert [
        choice.value for choice in NanofaasTUI._MAIN_MENU if hasattr(choice, "value")
    ] == [
        "build",
        "environment",
        "validation",
        "loadtest",
        "catalog",
        "profiles",
        "exit",
    ]


def test_workflow_dashboard_renders_persistent_openfaas_brand() -> None:
    dashboard = WorkflowDashboard(
        title="Validation",
        summary_lines=["Scenario: cli-stack"],
    )

    text = _render_dashboard_text(dashboard)

    assert "OpenFaaS" in text
