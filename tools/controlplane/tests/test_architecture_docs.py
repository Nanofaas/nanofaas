from __future__ import annotations

from pathlib import Path

from controlplane_tool.workspace.paths import resolve_workspace_path


def test_tool_readme_documents_package_architecture_checks() -> None:
    readme = resolve_workspace_path(Path("tools/controlplane/README.md")).read_text(
        encoding="utf-8"
    )

    assert "Package architecture checks" in readme
    assert "uv run lint-imports" in readme
    assert "uv run controlplane-package-report" in readme
    assert "uv run pydeps controlplane_tool" in readme
    assert "GitNexus" in readme


def test_tool_readme_documents_shared_selection_resolution_boundary() -> None:
    readme = resolve_workspace_path(Path("tools/controlplane/README.md")).read_text(
        encoding="utf-8"
    )

    assert "controlplane_tool.scenario.selection_resolution" in readme
    assert "ScenarioSelectionConfig" in readme
    assert "controlplane_tool.scenario.scenario_loader" in readme
